package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.analysis.config.ConfigFiles;
import com.devmanchego.javaguard.analysis.db.MigrationChecks;
import com.devmanchego.javaguard.build.BuildResult;
import com.devmanchego.javaguard.build.BuildRunner;
import com.devmanchego.javaguard.build.gradle.GradleBuildRunner;
import com.devmanchego.javaguard.build.maven.MavenBuildRunner;
import com.devmanchego.javaguard.change.ChangeTracker;
import com.devmanchego.javaguard.change.ConfigFindingPolicy;
import com.devmanchego.javaguard.change.GitClient;
import com.devmanchego.javaguard.change.Ledger;
import com.devmanchego.javaguard.change.LedgerEntry;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.integrity.TestIntegrityEngine;
import com.devmanchego.javaguard.loop.LoopGuard;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.HookEvent;
import com.devmanchego.javaguard.model.Suppression;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.project.BuildTool;
import com.devmanchego.javaguard.project.BuildToolLocator;
import com.devmanchego.javaguard.project.ModuleInfo;
import com.devmanchego.javaguard.project.ProjectDetector;
import com.devmanchego.javaguard.project.ReactorModules;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import com.devmanchego.javaguard.testing.AffectedTestSelector;
import com.devmanchego.javaguard.testing.TestSelection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * END_OF_TURN gate (specification &sect;10.3): compiles what the turn changed, runs the tests that
 * can be affected, re-checks file-local rules on files POST_EDIT did not see, and applies the end-of-turn
 * policy. Steps, in order:
 * <ol>
 *   <li><b>Changed set</b>: files of the session ledger, plus files the working tree differs in from what
 *       the previous run accounted for (shell edits), plus the files of the last blocked run. No relevant
 *       change: PASS at the cost of one {@code git status}.</li>
 *   <li><b>Compile</b> the affected modules and their dependents with the build tool.</li>
 *   <li><b>Tests</b> selected from the change, only if compilation succeeded.</li>
 *   <li><b>Deep analyzers</b> (none registered yet) and the classpath-cache refresh, only after a good compile.</li>
 *   <li><b>File-local checks</b> for changed files POST_EDIT did not analyze at their current content.</li>
 * </ol>
 * Everything is bounded by {@code fullGate.budgetSeconds}; exhausting it skips the remaining steps with a
 * note and never blocks by itself. Every run then goes through the {@link LoopGuard}, whose stop-guard detector
 * implements the consecutive-block guard of step 8 and may add a strategy hint or turn the verdict into HALT.
 */
public final class EndOfTurnGate {

    private static final Logger LOG = Logger.getLogger(EndOfTurnGate.class.getName());
    private static final Duration MIN_USEFUL_BUDGET = Duration.ofSeconds(5);
    private static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle", "build.gradle.kts",
            "settings.gradle", "settings.gradle.kts");

    private final JavaGuardPaths paths;
    private final JavaGuardConfig config;
    private final ChangeTracker tracker;
    private final WorkspaceChanges workspace;
    private final GitClient git;
    private final Function<ReactorModules, Optional<BuildRunner>> buildRunners;
    private final ClasspathRefresher refresher;
    private final List<DeepAnalyzer> deepAnalyzers;
    private final Clock clock;
    private final Duration budget;
    private final EndOfTurnState.Store stateStore;
    private final LoopGuard loopGuard;

    public EndOfTurnGate(JavaGuardPaths paths, JavaGuardConfig config, ChangeTracker tracker, WorkspaceChanges workspace,
                         GitClient git, Function<ReactorModules, Optional<BuildRunner>> buildRunners,
                         ClasspathRefresher refresher, List<DeepAnalyzer> deepAnalyzers, Clock clock) {
        this(paths, config, tracker, workspace, git, buildRunners, refresher, deepAnalyzers, clock,
                Duration.ofSeconds(config.fullGate().budgetSeconds()));
    }

    /** For tests that need a budget below the configuration's validated minimum. */
    EndOfTurnGate(JavaGuardPaths paths, JavaGuardConfig config, ChangeTracker tracker, WorkspaceChanges workspace,
                  GitClient git, Function<ReactorModules, Optional<BuildRunner>> buildRunners,
                  ClasspathRefresher refresher, List<DeepAnalyzer> deepAnalyzers, Clock clock, Duration budget) {
        this.paths = paths;
        this.config = config;
        this.tracker = tracker;
        this.workspace = workspace;
        this.git = git;
        this.buildRunners = buildRunners;
        this.refresher = refresher;
        this.deepAnalyzers = List.copyOf(deepAnalyzers);
        this.clock = clock;
        this.budget = budget;
        this.stateStore = new EndOfTurnState.Store(paths);
        this.loopGuard = LoopGuard.of(paths, config, clock);
    }

    /** Maven runner for the reactor, or nothing when no Maven executable can be found. */
    public static Function<ReactorModules, Optional<BuildRunner>> mavenRunners(JavaGuardPaths paths, JavaGuardConfig config,
                                                                              Map<String, String> env) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return reactor -> BuildToolLocator.maven(reactor.reactorRoot(), config.build().maven().useDaemon(), env, windows)
                .map(executable -> new MavenBuildRunner(new ProcessRunner(), executable, reactor, paths.projectRoot(),
                        Optional.of(paths.logsDir()), config.build().maven().offlineFirst(),
                        config.build().maven().extraArgs(), env));
    }

    /** Maven or Gradle runner, whichever the reactor uses; nothing when its executable or wrapper cannot be found. */
    public static Function<ReactorModules, Optional<BuildRunner>> runners(JavaGuardPaths paths, JavaGuardConfig config,
                                                                         Map<String, String> env) {
        Function<ReactorModules, Optional<BuildRunner>> maven = mavenRunners(paths, config, env);
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return reactor -> reactor.buildTool() != BuildTool.GRADLE ? maven.apply(reactor)
                : BuildToolLocator.gradle(reactor.reactorRoot(), env, windows)
                        .map(executable -> new GradleBuildRunner(new ProcessRunner(), executable, reactor, paths.projectRoot(),
                                Optional.of(paths.logsDir()), config.build().maven().offlineFirst(),
                                config.build().gradle().extraArgs(), env));
    }

    /**
     * The end-of-turn gate as the hook and {@code check --full} run it: the project's build tool, the classpath refresher and
     * every deep analyzer (API compatibility, SpotBugs/FindSecBugs, OSV, the repository re-check, configuration keys, HTTP contracts).
     */
    public static EndOfTurnGate standard(JavaGuardPaths paths, JavaGuardConfig config, Map<String, String> env, ChangeTracker tracker,
                                         GitClient git, Duration gitTimeout, Clock clock) {
        return new EndOfTurnGate(paths, config, tracker, new GitWorkspace(new ProcessRunner(), paths.projectRoot(), gitTimeout), git,
                runners(paths, config, env), ClasspathRefresher.maven(paths, config, env),
                List.of(new ApiCompatibilityAnalyzer(), new com.devmanchego.javaguard.analysis.spotbugs.SpotBugsAnalyzer(),
                        new com.devmanchego.javaguard.analysis.deps.OsvAnalyzer(),
                        new com.devmanchego.javaguard.analysis.RepositoryRecheckAnalyzer(),
                        new com.devmanchego.javaguard.analysis.config.ConfigKeyAnalyzer(),
                        new com.devmanchego.javaguard.analysis.http.HttpContractAnalyzer()), clock);
    }

    public GateResult run(HookEvent event) {
        long startNanos = System.nanoTime();
        String session = event.sessionId();
        Path root = paths.projectRoot();
        List<String> notes = new ArrayList<>();
        if (!config.fullGate().enabled()) {
            return result(Verdict.PASS, List.of(), List.of(), notes, List.of(), startNanos);
        }

        Optional<ModuleInfo> module = ProjectDetector.detect(root, configuredTool());
        Optional<ReactorModules> reactor = module.map(m -> ReactorModules.load(m.reactorRoot(), m.buildTool()));
        Ledger ledger = readLedger(session, notes);
        Optional<EndOfTurnState> state = stateStore.load(session);

        // --- 1. changed set -------------------------------------------------------------------
        Map<String, Path> candidates = new LinkedHashMap<>();
        ledger.files().keySet().forEach(rel -> addIfRelevant(candidates, root.resolve(rel), reactor));
        Optional<Set<Path>> workingTree = workspace.dirtyFiles();
        workingTree.ifPresent(dirty -> dirty.forEach(file -> addIfRelevant(candidates, file, reactor)));
        if (workingTree.isEmpty()) {
            workspace.problem().ifPresent(notes::add);
        }
        Map<String, String> hashes = new LinkedHashMap<>();
        candidates.forEach((rel, abs) -> hashes.put(rel, EndOfTurnState.hashOf(abs)));

        Set<String> changed = new LinkedHashSet<>();
        for (String rel : candidates.keySet()) {
            boolean changedSinceLastRun = state.isPresent()
                    ? !hashes.get(rel).equals(state.get().knownHashes().get(rel))
                    : ledger.files().containsKey(rel); // No earlier run: uncommitted files outside the ledger predate the session.
            if (changedSinceLastRun) {
                changed.add(rel);
            }
        }
        state.ifPresent(s -> s.pendingFiles().forEach(rel -> addPending(candidates, hashes, changed, rel, reactor)));

        if (changed.isEmpty()) {
            saveState(session, state, hashes, Set.of(), Verdict.PASS, 0, false);
            return loopGuard.apply(result(Verdict.PASS, List.of(), List.of(), notes, List.of(), startNanos),
                    new LoopGuard.Input(session, GateKind.END_OF_TURN, Map.of(), event.stopHookActive(),
                            event.agent().cliName(), event.transcriptPath()));
        }
        List<Path> changedAll = changed.stream().map(candidates::get).toList();
        List<Path> changedJava = changedAll.stream().filter(p -> p.toString().endsWith(".java")).toList();

        // --- 2-5. build, tests, deep analysis ---------------------------------------------------
        List<Finding> findings = new ArrayList<>();
        boolean skippedForBudget = runBuild(session, reactor, module, changedAll, changedJava, findings, notes, startNanos);

        // --- 6. file-local checks on what POST_EDIT did not see -----------------------------------
        List<Suppression> newSuppressions = new ArrayList<>();
        skippedForBudget |= fileLocalChecks(session, ledger, changed, candidates, hashes, findings, newSuppressions,
                notes, startNanos);

        // --- 7. policy -----------------------------------------------------------------------------
        EndOfTurnPolicy policy = new EndOfTurnPolicy(config);
        FastGatePolicy.Partition partition = policy.partition(findings);
        notes.addAll(partition.infrastructureNotes());
        Verdict verdict = policy.verdict(partition.blocking(), partition.advisory());

        saveState(session, state, hashes, changed, verdict, partition.blocking().size(), skippedForBudget);

        // --- 8. loop detection and escalation (specification section 13), before the report is written ------------
        Map<String, Path> looked = new LinkedHashMap<>();
        changed.forEach(rel -> looked.put(rel, candidates.get(rel)));
        GateResult raw = new GateResult(GateKind.END_OF_TURN, verdict, partition.blocking(),
                verdict == Verdict.PASS ? List.of() : partition.advisory(), notes, Optional.empty(), Optional.empty(),
                newSuppressions, Duration.ofNanos(System.nanoTime() - startNanos));
        GateResult escalated = loopGuard.apply(raw, new LoopGuard.Input(session, GateKind.END_OF_TURN, looked,
                event.stopHookActive(), event.agent().cliName(), event.transcriptPath()));
        return escalated.verdict() != Verdict.PASS ? new FastGatePipeline(paths, config, clock).withFullReport(escalated) : escalated;
    }

    // --- build ----------------------------------------------------------------------------------

    /** Returns whether a step was skipped because the budget ran out (the files then stay pending). */
    private boolean runBuild(String session, Optional<ReactorModules> reactor, Optional<ModuleInfo> module, List<Path> changedAll,
                             List<Path> changedJava, List<Finding> findings, List<String> notes, long startNanos) {
        if (reactor.isEmpty()) {
            notes.add("No Maven or Gradle project found; only file-local checks ran.");
            return false;
        }
        Optional<BuildRunner> runner = buildRunners.apply(reactor.get());
        if (runner.isEmpty()) {
            notes.add("No " + (reactor.get().buildTool() == BuildTool.GRADLE ? "Gradle" : "Maven")
                    + " executable or wrapper found; the compile and test steps were skipped.");
            return false;
        }
        Set<Path> moduleRoots = new LinkedHashSet<>();
        for (Path file : changedAll) {
            if (needsBuild(file)) {
                reactor.get().moduleOf(file).ifPresent(m -> moduleRoots.add(m.info().moduleRoot()));
            }
        }
        if (moduleRoots.isEmpty()) {
            // Only migrations or configuration changed: nothing to compile, but the deep analyzers still look at them.
            return deepAnalysis(session, reactor.get(), changedAll, findings, notes, startNanos);
        }
        if (remaining(startNanos).compareTo(MIN_USEFUL_BUDGET) < 0) {
            notes.add("The end-of-turn budget was exhausted before the compile step.");
            return true;
        }

        TestSelection selection = new AffectedTestSelector(reactor.get(), config.tests()).select(changedJava);
        notes.addAll(selection.notes());
        boolean skipped = false;
        if (runner.get().canCombine(selection)) {
            // One build-tool start for compile and tests (specification section 10.9).
            BuildRunner.CompileAndTest both = runner.get().compileAndTest(moduleRoots, selection, remaining(startNanos));
            findings.addAll(both.compile().findings());
            notes.addAll(both.compile().notes());
            if (!both.compile().success()) {
                return false;
            }
            both.tests().ifPresent(tests -> {
                findings.addAll(tests.findings());
                notes.addAll(tests.notes());
            });
        } else {
            BuildResult compile = runner.get().testCompile(moduleRoots, remaining(startNanos));
            findings.addAll(compile.findings());
            notes.addAll(compile.notes());
            if (!compile.success()) {
                return false;
            }
        }
        if (!selection.isEmpty() && !runner.get().canCombine(selection)) {
            if (remaining(startNanos).compareTo(MIN_USEFUL_BUDGET) < 0) {
                notes.add("The end-of-turn budget was exhausted before the test step; the tests were not run.");
                skipped = true;
            } else {
                BuildResult tests = runner.get().runTests(selection, remaining(startNanos));
                findings.addAll(tests.findings());
                notes.addAll(tests.notes());
            }
        }

        skipped |= deepAnalysis(session, reactor.get(), changedAll, findings, notes, startNanos);
        for (Path moduleRoot : moduleRoots) {
            reactor.get().all().stream().filter(m -> m.info().moduleRoot().equals(moduleRoot)).findFirst()
                    .ifPresent(m -> refresher.refreshIfStale(m.info(), remaining(startNanos)));
        }
        return skipped;
    }

    /** Returns whether the budget ran out before every deep analyzer ran. */
    private boolean deepAnalysis(String session, ReactorModules reactor, List<Path> changedAll, List<Finding> findings,
                                 List<String> notes, long startNanos) {
        java.util.function.Function<Path, Optional<String>> baselineOf = file -> {
            String rel = paths.projectRoot().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
            String text = baselineText(session, rel, file);
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        };
        for (DeepAnalyzer analyzer : deepAnalyzers) {
            if (remaining(startNanos).compareTo(MIN_USEFUL_BUDGET) < 0) {
                notes.add("The end-of-turn budget was exhausted before the deep analysis step.");
                return true;
            }
            try {
                findings.addAll(ConfigFindingPolicy.of(config).apply(analyzer.analyze(new DeepAnalyzer.Context(changedAll,
                        reactor, remaining(startNanos), baselineOf, config, paths))));
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "A deep analyzer failed; skipping it.", e);
            }
        }
        return false;
    }

    /** Java sources and build files are compiled and tested; migrations and configuration are only analyzed. */
    private static boolean needsBuild(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".java") || BUILD_FILES.contains(name) || file.toString().replace('\\', '/').endsWith("/.mvn/maven.config");
    }

    // --- file-local checks ----------------------------------------------------------------------

    private boolean fileLocalChecks(String session, Ledger ledger, Set<String> changed, Map<String, Path> candidates,
                                 Map<String, String> hashes, List<Finding> findings, List<Suppression> newSuppressions,
                                 List<String> notes, long startNanos) {
        List<FastGatePipeline.FileUnderCheck> files = new ArrayList<>();
        for (String rel : changed) {
            Path abs = candidates.get(rel);
            // Deleted Java files are checked too (JG-TST-008); build files are checked for test-skipping (JG-TST-009);
            // migrations, deleted ones included (JG-DB-002), and configuration files (JG-CFG-001).
            boolean exists = abs != null && Files.isRegularFile(abs);
            boolean checkable = rel.endsWith(".java") || MigrationChecks.isMigration(rel)
                    || (TestIntegrityEngine.isBuildFile(rel) || ConfigFiles.isConfigFile(rel)) && exists;
            if (abs == null || !checkable || analyzedByPostEdit(ledger, rel, hashes)) {
                continue;
            }
            files.add(new FastGatePipeline.FileUnderCheck(abs, rel, baselineText(session, rel, abs)));
        }
        if (files.isEmpty()) {
            return false;
        }
        Duration remaining = remaining(startNanos);
        if (remaining.compareTo(MIN_USEFUL_BUDGET) < 0) {
            notes.add("The end-of-turn budget was exhausted before the file-local checks.");
            return true;
        }
        FastGatePipeline.Analysis analysis = new FastGatePipeline(paths, config, clock, remaining, git)
                .analyze(files, List.of(), false);
        findings.addAll(analysis.findings());
        newSuppressions.addAll(analysis.newSuppressions());
        notes.addAll(analysis.notes());
        return false;
    }

    /** POST_EDIT saw the file at exactly its current content. */
    private static boolean analyzedByPostEdit(Ledger ledger, String rel, Map<String, String> hashes) {
        LedgerEntry entry = ledger.files().get(rel);
        return entry != null && hashes.get(rel).equals(entry.lastSeenHash())
                && entry.gateRuns().stream().anyMatch(run -> run.endsWith(" " + GateKind.POST_EDIT.cliName()));
    }

    private String baselineText(String session, String rel, Path abs) {
        try {
            Optional<ChangeTracker.Baseline> baseline = tracker.baseline(session, rel);
            if (baseline.isPresent()) {
                return baseline.get().text();
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not read the baseline of " + rel, e);
        }
        return git.showHead(abs).map(bytes -> new String(bytes, StandardCharsets.UTF_8)).orElse("");
    }

    // --- state and helpers ------------------------------------------------------------------------

    /** Persists the run; returns the new consecutive-block count. */
    private int saveState(String session, Optional<EndOfTurnState> previous, Map<String, String> hashes,
                          Set<String> changed, Verdict verdict, int blockingCount, boolean incomplete) {
        Map<String, String> known = new LinkedHashMap<>(previous.map(EndOfTurnState::knownHashes).orElse(Map.of()));
        known.putAll(hashes);
        int consecutive = 0;
        if (verdict == Verdict.BLOCK) {
            boolean notImproving = previous.isPresent() && previous.get().consecutiveBlocks() > 0
                    && blockingCount >= previous.get().lastBlockingCount();
            consecutive = notImproving ? previous.get().consecutiveBlocks() + 1 : 1;
        }
        List<String> pending = verdict == Verdict.BLOCK || incomplete ? List.copyOf(changed) : List.of();
        stateStore.save(session, new EndOfTurnState(EndOfTurnState.CURRENT_VERSION, clock.instant().toString(), known,
                pending, consecutive, verdict == Verdict.BLOCK ? blockingCount : 0));
        return consecutive;
    }

    private void addIfRelevant(Map<String, Path> candidates, Path file, Optional<ReactorModules> reactor) {
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.startsWith(paths.projectRoot()) || absolute.equals(paths.projectRoot())) {
            return;
        }
        String rel = paths.projectRoot().relativize(absolute).toString().replace('\\', '/');
        if (isRelevant(absolute, rel, reactor)) {
            candidates.putIfAbsent(rel, absolute);
        }
    }

    private void addPending(Map<String, Path> candidates, Map<String, String> hashes, Set<String> changed, String rel,
                            Optional<ReactorModules> reactor) {
        Path absolute = paths.projectRoot().resolve(rel).normalize();
        if (isRelevant(absolute, rel, reactor)) {
            candidates.putIfAbsent(rel, absolute);
            hashes.putIfAbsent(rel, EndOfTurnState.hashOf(absolute));
            changed.add(rel);
        }
    }

    /** A Java source in a module's source roots, a build file, a migration or a configuration file; never build output. */
    static boolean isRelevant(Path absolute, String rel, Optional<ReactorModules> reactor) {
        String slashed = "/" + rel;
        if (slashed.contains("/target/") || slashed.contains("/build/") || slashed.contains("/.javaguard/")
                || slashed.contains("/.git/")) {
            return false;
        }
        String name = absolute.getFileName().toString();
        if (BUILD_FILES.contains(name) || slashed.endsWith("/.mvn/maven.config") || MigrationChecks.isMigration(rel)
                || ConfigFiles.isConfigFile(rel)) {
            return true;
        }
        if (!name.endsWith(".java")) {
            return false;
        }
        return reactor.isEmpty() || reactor.get().moduleOf(absolute)
                .map(m -> m.info().isMainSource(absolute) || m.info().isTestSource(absolute)).orElse(false);
    }

    private Ledger readLedger(String session, List<String> notes) {
        try {
            return tracker.ledger(session);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not read the session ledger; relying on the working tree only.", e);
            notes.add("The session ledger could not be read; only uncommitted changes were considered.");
            return Ledger.empty(session);
        }
    }

    private Optional<BuildTool> configuredTool() {
        return switch (config.build().tool()) {
            case AUTO -> Optional.empty();
            case MAVEN -> Optional.of(BuildTool.MAVEN);
            case GRADLE -> Optional.of(BuildTool.GRADLE);
        };
    }

    private Duration remaining(long startNanos) {
        Duration left = budget.minus(Duration.ofNanos(System.nanoTime() - startNanos));
        return left.isNegative() ? Duration.ZERO : left;
    }

    private GateResult result(Verdict verdict, List<Finding> blocking, List<Finding> advisory, List<String> notes,
                              List<Suppression> newSuppressions, long startNanos) {
        boolean report = verdict != Verdict.PASS;
        GateResult result = new GateResult(GateKind.END_OF_TURN, verdict, blocking, report ? advisory : List.of(), notes,
                Optional.empty(), Optional.empty(), newSuppressions,
                Duration.ofNanos(System.nanoTime() - startNanos));
        return report ? new FastGatePipeline(paths, config, clock).withFullReport(result) : result;
    }
}
