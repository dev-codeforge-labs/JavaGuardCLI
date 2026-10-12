package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.analysis.EntityIndex;
import com.devmanchego.javaguard.analysis.config.ConfigFiles;
import com.devmanchego.javaguard.analysis.config.ConfigSecretCheck;
import com.devmanchego.javaguard.analysis.db.MigrationChecks;
import com.devmanchego.javaguard.analysis.deps.DependencyChecks;
import com.devmanchego.javaguard.change.BaselineFindingsCache;
import com.devmanchego.javaguard.change.ConfigFindingPolicy;
import com.devmanchego.javaguard.change.GitClient;
import com.devmanchego.javaguard.change.ProcessGitClient;
import com.devmanchego.javaguard.change.LineRanges;
import com.devmanchego.javaguard.change.ScopedAnalysis;
import com.devmanchego.javaguard.change.SuppressionFilter;
import com.devmanchego.javaguard.change.SuppressionScanner;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.integrity.TestIntegrityEngine;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.Suppression;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.project.BuildTool;
import com.devmanchego.javaguard.project.ModuleInfo;
import com.devmanchego.javaguard.project.ProjectDetector;
import com.devmanchego.javaguard.report.HumanReportWriter;
import com.devmanchego.javaguard.state.JavaGuardPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The fast gate's per-file pipeline (specification &sect;10.2): skip rules, parse + AST
 * rules + PMD scoped to what the session introduced, in-process compilation, suppressions,
 * policy. Shared by the POST_EDIT hook ({@link PostEditGate}) and the manual {@code check}
 * command, which differ only in where the baseline text comes from.
 *
 * <p>Files are analyzed in parallel (at most {@value #MAX_PARALLEL}); the whole run is bounded by
 * {@code fastGate.budgetMillis}. Exhausting the budget yields a partial result and a note, never a
 * BLOCK of its own.
 */
public final class FastGatePipeline {

    private static final Logger LOG = Logger.getLogger(FastGatePipeline.class.getName());

    static final int MAX_PARALLEL = 4;
    private static final Pattern GENERATED_ANNOTATION = Pattern.compile("@(?:\\w+\\.)*Generated\\b");

    /**
     * A file to check. {@code baseline} is the text findings are compared against: the session
     * baseline for hooks, {@code HEAD} or empty for manual runs.
     */
    public record FileUnderCheck(Path absolute, String relative, String baseline) {
    }

    private record Analyzed(FileUnderCheck file, String current, ScopedAnalysis.Result result,
                            Optional<ModuleInfo> module, boolean compilable) {
    }

    /**
     * What a file under check is: source to analyze, a deleted source, a build file, a database migration (present or
     * deleted), a Spring Boot configuration file, or nothing to look at.
     */
    private enum Kind { JAVA, DELETED_JAVA, BUILD_FILE, MIGRATION, CONFIG, SKIP }

    private final JavaGuardPaths paths;
    private final JavaGuardConfig config;
    private final Clock clock;
    private final Duration budget;
    private final TestIntegrityEngine integrity = new TestIntegrityEngine();
    private final GitClient git;

    public FastGatePipeline(JavaGuardPaths paths, JavaGuardConfig config, Clock clock) {
        this(paths, config, clock, Duration.ofMillis(config.fastGate().budgetMillis()));
    }

    /** For tests that need a budget below the configuration's validated minimum. */
    FastGatePipeline(JavaGuardPaths paths, JavaGuardConfig config, Clock clock, Duration budget) {
        this(paths, config, clock, budget, new ProcessGitClient(new ProcessRunner(), Duration.ofSeconds(5)));
    }

    /** {@code git} answers whether a migration is committed (JG-DB-001/002 apply to committed migrations only). */
    FastGatePipeline(JavaGuardPaths paths, JavaGuardConfig config, Clock clock, Duration budget, GitClient git) {
        this.paths = paths;
        this.config = config;
        this.clock = clock;
        this.budget = budget;
        this.git = git;
    }

    /** What the file-local checks and, optionally, the in-process compiler found, before any gate policy. */
    record Analysis(List<Finding> findings, List<Suppression> newSuppressions, List<String> notes, Duration elapsed) {
        Analysis {
            findings = List.copyOf(findings);
            newSuppressions = List.copyOf(newSuppressions);
            notes = List.copyOf(notes);
        }
    }

    /** POST_EDIT / {@code check}: analysis, then the fast-gate policy (specification section 10.2 step 9). */
    public GateResult run(GateKind gate, List<FileUnderCheck> files, List<String> carriedNotes) {
        return run(gate, files, carriedNotes, java.util.function.UnaryOperator.identity());
    }

    /**
     * As {@link #run(GateKind, List, List)}, with {@code beforeReport} applied to the result before the full report is
     * written: the POST_EDIT gate uses it for loop detection, which may add an escalation or turn the verdict into HALT.
     */
    public GateResult run(GateKind gate, List<FileUnderCheck> files, List<String> carriedNotes,
                          java.util.function.UnaryOperator<GateResult> beforeReport) {
        Analysis analysis = analyze(files, carriedNotes, config.fastGate().inProcessCompile());
        List<String> notes = new ArrayList<>(analysis.notes());

        FastGatePolicy policy = new FastGatePolicy(config);
        FastGatePolicy.Partition partition = policy.partition(analysis.findings());
        notes.addAll(partition.infrastructureNotes());
        Verdict verdict = policy.verdict(partition.blocking(), partition.advisory());
        boolean report = verdict != Verdict.PASS;
        GateResult result = new GateResult(gate, verdict, partition.blocking(),
                report ? partition.advisory() : List.of(), notes, Optional.empty(), Optional.empty(),
                analysis.newSuppressions(), analysis.elapsed());
        GateResult decorated = beforeReport.apply(result);
        return decorated.verdict() != Verdict.PASS ? withFullReport(decorated) : decorated;
    }

    /**
     * Skip rules, parse, AST rules, PMD, suppressions and (optionally) in-process compilation, scoped to
     * what the session introduced. The end-of-turn gate calls this with {@code compile=false}: its build
     * tool has just compiled everything for real.
     */
    Analysis analyze(List<FileUnderCheck> files, List<String> carriedNotes, boolean compile) {
        long startNanos = System.nanoTime();
        List<String> notes = new ArrayList<>(carriedNotes);
        List<Finding> findings = new ArrayList<>();
        List<Suppression> newSuppressions = new ArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(MAX_PARALLEL, runnable -> {
            Thread thread = new Thread(runnable, "javaguard-fast-gate");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Analyzed> analyzed = analyzeFiles(executor, files, startNanos, notes);
            for (Analyzed a : analyzed) {
                findings.addAll(a.result().reported());
                newSuppressions.addAll(a.result().newSuppressions());
            }
            if (compile) {
                compile(executor, analyzed, startNanos, findings, notes);
            }
        } finally {
            executor.shutdownNow();
        }
        return new Analysis(findings, newSuppressions, notes, elapsedSince(startNanos));
    }

    // --- analysis -----------------------------------------------------------------------------

    private List<Analyzed> analyzeFiles(ExecutorService executor, List<FileUnderCheck> files, long startNanos,
                                        List<String> notes) {
        ConfigFindingPolicy findingPolicy = ConfigFindingPolicy.of(config);
        ScopedAnalysis scoped = new ScopedAnalysis(new BaselineFindingsCache(paths.baselineFindingsCacheDir()),
                findingPolicy, config.suppressions().allowAgentAdded());
        Map<Path, Optional<ModuleInfo>> modules = new LinkedHashMap<>();
        Map<Path, FastFileAnalyzer> analyzers = new LinkedHashMap<>();

        Map<FileUnderCheck, Future<Analyzed>> pending = new LinkedHashMap<>();
        for (FileUnderCheck file : files) {
            Kind kind = kindOf(file, findingPolicy);
            if (kind == Kind.SKIP) {
                continue;
            }
            if (kind == Kind.MIGRATION) {
                pending.put(file, executor.submit(() -> migration(file, findingPolicy)));
                continue;
            }
            Optional<ModuleInfo> module = modules.computeIfAbsent(file.absolute().getParent(),
                    dir -> ProjectDetector.detect(file.absolute(), configuredTool()));
            if (kind == Kind.DELETED_JAVA) {
                if (isTestSource(file, module)) {
                    pending.put(file, executor.submit(() -> deletedTestSource(file, module, findingPolicy)));
                }
                continue;
            }
            String current;
            try {
                current = Files.readString(file.absolute(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not read " + file.absolute() + "; skipping it.", e);
                continue;
            }
            if (kind == Kind.BUILD_FILE) {
                String text = current;
                pending.put(file, executor.submit(() -> buildFile(file, text, module, findingPolicy)));
                continue;
            }
            if (kind == Kind.CONFIG) {
                String text = current;
                pending.put(file, executor.submit(() -> configFile(file, text, module, scoped)));
                continue;
            }
            if (module.isPresent() && !inSourceRoots(module.get(), file.absolute())) {
                continue; // Specification section 10.2 step 1: outside the module's source roots.
            }
            if (GENERATED_ANNOTATION.matcher(current).find()) {
                continue;
            }
            String text = current;
            FastFileAnalyzer analyzer = analyzers.computeIfAbsent(module.map(ModuleInfo::moduleRoot).orElse(paths.projectRoot()),
                    root -> analyzerFor(module));
            pending.put(file, executor.submit(() -> javaSource(file, text, module, scoped, analyzer, findingPolicy)));
        }

        List<Analyzed> done = new ArrayList<>();
        int unfinished = 0;
        for (Map.Entry<FileUnderCheck, Future<Analyzed>> entry : pending.entrySet()) {
            Future<Analyzed> future = entry.getValue();
            try {
                done.add(future.get(remainingMillis(startNanos), TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                future.cancel(true);
                unfinished++;
            } catch (ExecutionException e) {
                LOG.log(Level.WARNING, "Analysis of " + entry.getKey().relative() + " failed; skipping it.", e.getCause());
                notes.add("Analysis of " + entry.getKey().relative() + " failed internally and was skipped (see the log).");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                unfinished += 1;
                break;
            }
        }
        if (unfinished > 0) {
            notes.add("Fast-gate budget (" + budget.toMillis() + " ms) exhausted: " + unfinished
                    + " file(s) were not analyzed.");
        }
        return done;
    }

    /**
     * The analyzer of one module: the frameworks detected on it gate the Spring/JPA rules, its source roots feed the
     * entity index, and {@code arch.rules} apply everywhere. Without a module nothing is detected, so those rules
     * stay silent (specification &sect;11.2).
     */
    private FastFileAnalyzer analyzerFor(Optional<ModuleInfo> module) {
        return new FastFileAnalyzer(config.pmd().enabled(), module.map(ModuleInfo::frameworks).orElse(java.util.Set.of()),
                module.map(m -> EntityIndex.scanning(m.mainSourceRoots())).orElse(EntityIndex.empty()),
                config.arch().rules());
    }

    /** Specification section 10.2 step 1: generated and excluded files are skipped; so is anything that is not Java or a build file. */
    private static Kind kindOf(FileUnderCheck file, ConfigFindingPolicy findingPolicy) {
        String name = file.relative();
        if (isGeneratedPath(name) || findingPolicy.isExcluded(name)) {
            return Kind.SKIP;
        }
        boolean exists = Files.isRegularFile(file.absolute());
        if (MigrationChecks.isMigration(name)) {
            return Kind.MIGRATION; // Before .java: a Flyway Java migration is judged as a migration and as Java source.
        }
        if (name.endsWith(".java")) {
            return exists ? Kind.JAVA : Kind.DELETED_JAVA;
        }
        if (exists && ConfigFiles.isConfigFile(name)) {
            return Kind.CONFIG;
        }
        return exists && TestIntegrityEngine.isBuildFile(name) ? Kind.BUILD_FILE : Kind.SKIP;
    }

    private static boolean isTestSource(FileUnderCheck file, Optional<ModuleInfo> module) {
        return module.map(m -> m.isTestSource(file.absolute().toAbsolutePath().normalize())).orElse(false)
                || TestIntegrityEngine.isTestSource(file.relative());
    }

    /** A Java source: scoped rules, then the test-integrity rules that need the baseline (JG-TST-001..012). */
    private Analyzed javaSource(FileUnderCheck file, String current, Optional<ModuleInfo> module, ScopedAnalysis scoped,
                                FastFileAnalyzer analyzer, ConfigFindingPolicy findingPolicy) {
        ScopedAnalysis.Result result = scoped.run(file.relative(), file.baseline(), current, analyzer, analyzer.key());
        if (isTestSource(file, module)) {
            List<Finding> integrityFindings = findingPolicy.apply(integrity.checkJava(file.relative(), file.baseline(), current));
            if (!integrityFindings.isEmpty()) {
                List<Finding> kept = SuppressionFilter.apply(file.relative(), integrityFindings,
                        SuppressionScanner.scan(current), SuppressionScanner.scan(file.baseline()), result.changedLines(),
                        config.suppressions().allowAgentAdded()).kept();
                List<Finding> all = new ArrayList<>(result.reported());
                all.addAll(kept);
                result = new ScopedAnalysis.Result(all, result.changedLines(), result.newSuppressions());
            }
        }
        return new Analyzed(file, current, result, module, true);
    }

    /** JG-TST-008: a test source that held tests no longer exists. */
    private Analyzed deletedTestSource(FileUnderCheck file, Optional<ModuleInfo> module, ConfigFindingPolicy findingPolicy) {
        List<Finding> findings = findingPolicy.apply(integrity.checkDeleted(file.relative(), file.baseline()));
        return new Analyzed(file, "", new ScopedAnalysis.Result(findings, LineRanges.empty(), List.of()), module, false);
    }

    /** JG-TST-009: {@code pom.xml} and {@code .mvn/maven.config} that switch tests off. */
    private Analyzed buildFile(FileUnderCheck file, String current, Optional<ModuleInfo> module,
                               ConfigFindingPolicy findingPolicy) {
        List<Finding> findings = new ArrayList<>(integrity.checkBuildFile(file.relative(), file.baseline(), current));
        findings.addAll(DependencyChecks.check(file.relative(), file.baseline(), current));
        findings = findingPolicy.apply(findings);
        return new Analyzed(file, current, new ScopedAnalysis.Result(findings, LineRanges.empty(), List.of()), module, false);
    }

    /**
     * JG-DB-001..003. The baseline counts as applied when it existed and, in a git repository, is committed: a migration
     * that only exists in the working tree has not reached a shared database.
     */
    private Analyzed migration(FileUnderCheck file, ConfigFindingPolicy findingPolicy) throws IOException {
        Optional<String> current = Files.isRegularFile(file.absolute())
                ? Optional.of(Files.readString(file.absolute(), StandardCharsets.UTF_8)) : Optional.empty();
        boolean applied = !file.baseline().isEmpty()
                && (!insideGitRepository(file.absolute()) || git.showHead(file.absolute()).isPresent());
        List<Finding> findings = findingPolicy.apply(MigrationChecks.check(file.relative(), file.absolute(), file.baseline(), current,
                applied));
        if (current.isPresent() && file.relative().endsWith(".java")) {
            // A Flyway Java migration is still Java source: the file-local rules apply to it as to any other class.
            Optional<ModuleInfo> module = ProjectDetector.detect(file.absolute(), configuredTool());
            ScopedAnalysis scoped = new ScopedAnalysis(new BaselineFindingsCache(paths.baselineFindingsCacheDir()), findingPolicy,
                    config.suppressions().allowAgentAdded());
            Analyzed java = javaSource(file, current.get(), module, scoped, analyzerFor(module), findingPolicy);
            List<Finding> all = new ArrayList<>(java.result().reported());
            all.addAll(findings);
            return new Analyzed(file, current.get(), new ScopedAnalysis.Result(all, java.result().changedLines(),
                    java.result().newSuppressions()), module, true);
        }
        return new Analyzed(file, current.orElse(""), new ScopedAnalysis.Result(findings, LineRanges.empty(), List.of()),
                Optional.empty(), false);
    }

    /** JG-CFG-001 on a Spring Boot configuration file, scoped to what the session added. */
    private Analyzed configFile(FileUnderCheck file, String current, Optional<ModuleInfo> module, ScopedAnalysis scoped) {
        ScopedAnalysis.Result result = scoped.run(file.relative(), file.baseline(), current, new ConfigSecretCheck(), ConfigSecretCheck.KEY);
        return new Analyzed(file, current, result, module, false);
    }

    private static boolean insideGitRepository(Path file) {
        for (Path dir = file.toAbsolutePath().normalize().getParent(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".git"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGeneratedPath(String relative) {
        String p = "/" + relative.replace('\\', '/');
        return p.contains("/target/") || p.contains("/build/") || p.contains("generated-sources");
    }

    private static boolean inSourceRoots(ModuleInfo module, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        return module.isMainSource(absolute) || module.isTestSource(absolute);
    }

    private Optional<BuildTool> configuredTool() {
        return switch (config.build().tool()) {
            case AUTO -> Optional.empty();
            case MAVEN -> Optional.of(BuildTool.MAVEN);
            case GRADLE -> Optional.of(BuildTool.GRADLE);
        };
    }

    // --- compilation --------------------------------------------------------------------------

    private void compile(ExecutorService executor, List<Analyzed> analyzed, long startNanos,
                         List<Finding> findings, List<String> notes) {
        Map<Path, List<Analyzed>> byModule = new LinkedHashMap<>();
        for (Analyzed a : analyzed) {
            boolean parseFailure = a.result().reported().stream()
                    .anyMatch(f -> "JG-SYN-001".equals(f.ruleId()));
            if (parseFailure) {
                continue; // Specification section 10.2 step 2: nothing else runs on a file that does not parse.
            }
            if (a.module().isEmpty() || !a.compilable()) {
                continue;
            }
            byModule.computeIfAbsent(a.module().get().moduleRoot(), k -> new ArrayList<>()).add(a);
        }
        if (byModule.isEmpty()) {
            return;
        }
        FastCompileStage stage = new FastCompileStage(paths, config.errorprone().enabled());
        ConfigFindingPolicy findingPolicy = ConfigFindingPolicy.of(config);
        for (List<Analyzed> group : byModule.values()) {
            ModuleInfo module = group.get(0).module().orElseThrow();
            List<Path> toCompile = group.stream().map(a -> a.file().absolute().toAbsolutePath().normalize()).toList();
            Future<FastCompileStage.Outcome> future = executor.submit(() -> stage.compile(module, toCompile));
            try {
                FastCompileStage.Outcome outcome = future.get(remainingMillis(startNanos), TimeUnit.MILLISECONDS);
                notes.addAll(outcome.notes());
                scopeCompileFindings(outcome.findings(), group, findingPolicy, findings);
            } catch (TimeoutException e) {
                future.cancel(true);
                notes.add("In-process compilation skipped: fast-gate budget (" + budget.toMillis() + " ms) exhausted.");
                return;
            } catch (ExecutionException e) {
                LOG.log(Level.WARNING, "In-process compilation failed", e.getCause());
                notes.add("In-process compilation failed internally and was skipped (see the log).");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * javac errors in an edited file are all reported (an edit that leaves the file non-compiling is
     * the agent's to fix, and they are advisory here). Error Prone findings are reported only on
     * lines this session changed, so legacy patterns are not blamed on the agent.
     */
    private void scopeCompileFindings(List<Finding> compileFindings, List<Analyzed> group,
                                      ConfigFindingPolicy findingPolicy, List<Finding> out) {
        Map<Path, Analyzed> byFile = new LinkedHashMap<>();
        group.forEach(a -> byFile.put(Path.of(a.file().relative()), a));
        for (Finding f : findingPolicy.apply(compileFindings)) {
            Analyzed owner = f.file().map(byFile::get).orElse(null);
            if (owner == null) {
                continue;
            }
            LineRanges changed = owner.result().changedLines();
            boolean isJavac = "JAVAC".equals(f.ruleId());
            if (!isJavac && !changed.contains(f.line())) {
                continue;
            }
            var currentMarkers = SuppressionScanner.scan(owner.current());
            var baselineMarkers = SuppressionScanner.scan(owner.file().baseline());
            out.addAll(SuppressionFilter.apply(owner.file().relative(), List.of(f), currentMarkers, baselineMarkers,
                    changed, config.suppressions().allowAgentAdded()).kept());
        }
    }

    // --- result -------------------------------------------------------------------------------

    GateResult withFullReport(GateResult result) {
        try {
            Path written = HumanReportWriter.write(paths, result, clock.instant());
            Path relative = paths.projectRoot().relativize(written.toAbsolutePath().normalize());
            return new GateResult(result.gate(), result.verdict(), result.blocking(), result.advisory(),
                    result.notes(), result.escalation(), Optional.of(relative), result.newSuppressions(),
                    result.elapsed(), result.solution());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not write the full report; the agent report is unaffected.", e);
            return result;
        }
    }

    private long remainingMillis(long startNanos) {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        return Math.max(1, budget.toMillis() - elapsedMillis);
    }

    private static Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
