package com.devmanchego.javaguard.analysis.spotbugs;

import com.devmanchego.javaguard.analysis.spotbugs.SpotBugsRunner.Bug;
import com.devmanchego.javaguard.build.ClasspathCache;
import com.devmanchego.javaguard.build.ClasspathCacheStore;
import com.devmanchego.javaguard.change.ChangedLines;
import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.change.LineRanges;
import com.devmanchego.javaguard.change.SuppressionMarker;
import com.devmanchego.javaguard.change.SuppressionScanner;
import com.devmanchego.javaguard.gate.DeepAnalyzer;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.BuildTool;
import com.devmanchego.javaguard.project.ModuleInfo;
import com.devmanchego.javaguard.project.ReactorModules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * SpotBugs + FindSecBugs on the class files of the turn's changed main classes (specification &sect;10.3 step 5, catalog &sect;5).
 *
 * <p>Run after the build compiled them; auxiliary classpath = the module's classpath cache when there is one. Only bugs
 * on lines the session changed (or anywhere in a file it created) are reported, so legacy findings in a class the
 * agent merely touched stay out. Categories and ranks follow the catalog: {@code CORRECTNESS}, {@code MT_CORRECTNESS},
 * {@code SECURITY}, {@code MALICIOUS_CODE} (always INFO) and a short list of {@code PERFORMANCE} patterns; rank 1-4
 * CRITICAL, 5-9 MAJOR, 10-14 INFO, 15+ dropped; the noisy patterns of the catalog are excluded. A bug that a JavaGuard
 * rule covers is reported <em>under that rule's id</em> ({@code SQL_INJECTION_* -> JG-SEC-001}), with
 * {@code detail.alsoReportedBy=spotbugs}, so suppressions, configuration and the agent's remediation text are the same
 * whichever engine saw it. Suppressions ({@code javaguard:ignore <id>}, either the JG id or {@code SB:<pattern>}) apply.
 */
public final class SpotBugsAnalyzer implements DeepAnalyzer {

    private static final Logger LOG = Logger.getLogger(SpotBugsAnalyzer.class.getName());
    private static final Duration DEFAULT_MIN_BUDGET = Duration.ofSeconds(20);
    private static final Duration DEFAULT_RESERVE = Duration.ofSeconds(5);
    private static final Set<String> CATEGORIES = Set.of("CORRECTNESS", "MT_CORRECTNESS", "SECURITY", "MALICIOUS_CODE", "PERFORMANCE");
    private static final Pattern PERFORMANCE_KEPT = Pattern.compile("SBSC_.*|DM_.*_CTOR|WMI_WRONG_MAP_ITERATOR");
    private static final Pattern EXCLUDED = Pattern.compile(
            "EI_EXPOSE_REP.*|SE_.*|SPRING_ENDPOINT|CRLF_INJECTION_LOGS|INFORMATION_EXPOSURE_THROUGH_AN_ERROR_MESSAGE");

    /** SpotBugs/FindSecBugs patterns that a JavaGuard rule also covers, in matching order. */
    private static final Map<Pattern, String[]> OVERLAPS = new LinkedHashMap<>();

    static {
        overlap("SQL_INJECTION.*|SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE|SPRING_ENTITYMANAGER_.*", "JG-SEC-001", "SQL built by concatenation");
        overlap("COMMAND_INJECTION", "JG-SEC-002", "Command built from variable input");
        overlap("OBJECT_DESERIALIZATION|JACKSON_UNSAFE_DESERIALIZATION|YAML_LOAD", "JG-SEC-003", "Unsafe deserialization");
        overlap("XXE_.*|XML_DECODER", "JG-SEC-004", "XML parser open to XXE");
        overlap("WEAK_MESSAGE_DIGEST.*|DES_USAGE|TDES_USAGE|ECB_MODE|STATIC_IV|RSA_KEY_SIZE|CIPHER_INTEGRITY|DEFAULT_HTTP_CLIENT.*",
                "JG-SEC-005", "Weak cryptography");
        overlap("PREDICTABLE_RANDOM.*", "JG-SEC-006", "Predictable randomness for secrets");
        overlap("WEAK_TRUST_MANAGER|WEAK_HOSTNAME_VERIFIER|SSL_CONTEXT|INSECURE_TRUST_MANAGER|UNSAFE_HASH_EQUALS_NOT",
                "JG-SEC-007", "TLS verification disabled");
        overlap("HARD_CODE_PASSWORD|HARD_CODE_KEY", "JG-SEC-008", "Hard-coded secret");
        overlap("PATH_TRAVERSAL_IN|PATH_TRAVERSAL_OUT", "JG-SEC-009", "Path from request input");
    }

    private static void overlap(String patterns, String id, String title) {
        OVERLAPS.put(Pattern.compile(patterns), new String[] {id, title});
    }

    private final SpotBugsRunner runner;
    private final Duration minBudget;
    private final Duration reserve;

    public SpotBugsAnalyzer() {
        this(new SpotBugsRunner());
    }

    public SpotBugsAnalyzer(SpotBugsRunner runner) {
        this(runner, DEFAULT_MIN_BUDGET, DEFAULT_RESERVE);
    }

    /** {@code minBudget}: below this much time left the analysis is skipped; {@code reserve}: kept back for the rest of the gate. */
    SpotBugsAnalyzer(SpotBugsRunner runner, Duration minBudget, Duration reserve) {
        this.runner = runner;
        this.minBudget = minBudget;
        this.reserve = reserve;
    }

    @Override
    public List<Finding> analyze(Context context) {
        if (!context.config().spotbugs().enabled() || context.remaining().compareTo(minBudget) < 0) {
            return List.of();
        }
        Map<ModuleInfo, Map<String, Path>> sourcesByModule = changedSources(context);
        List<Finding> findings = new ArrayList<>();
        Duration remaining = context.remaining();
        for (Map.Entry<ModuleInfo, Map<String, Path>> entry : sourcesByModule.entrySet()) {
            ModuleInfo module = entry.getKey();
            Map<String, Path> sources = entry.getValue();
            List<Path> classes = classFiles(module, sources.keySet());
            if (classes.isEmpty()) {
                continue;
            }
            List<Bug> bugs = runWithTimeout(classes, auxiliaryClasspath(context, module, classes), remaining.minus(reserve));
            for (Bug bug : bugs) {
                Optional<Path> source = Optional.ofNullable(sources.get(outer(bug.className())));
                source.flatMap(file -> toFinding(context, bug, file)).ifPresent(findings::add);
            }
        }
        return mergeSameRule(findings);
    }

    /** Two patterns for the same JG rule on the same line are one problem: keep the most severe, list both patterns. */
    private static List<Finding> mergeSameRule(List<Finding> findings) {
        Map<String, Finding> byKey = new LinkedHashMap<>();
        for (Finding finding : findings) {
            String key = finding.ruleId() + "|" + finding.file().map(Path::toString).orElse("") + "|" + finding.line();
            Finding kept = byKey.get(key);
            if (kept == null) {
                byKey.put(key, finding);
                continue;
            }
            Finding winner = finding.severity().ordinal() < kept.severity().ordinal() ? finding : kept;
            Finding other = winner == kept ? finding : kept;
            Map<String, String> detail = new LinkedHashMap<>(winner.detail());
            detail.put("pattern", winner.detail().get("pattern") + "," + other.detail().get("pattern"));
            byKey.put(key, new Finding(winner.ruleId(), winner.title(), winner.category(), winner.severity(), winner.engine(), winner.file(),
                    winner.line(), winner.column(), winner.endLine(), winner.message(), winner.remediation(), winner.snippet(), detail,
                    winner.fingerprint()));
        }
        return new ArrayList<>(byKey.values());
    }

    // --- what to analyze -------------------------------------------------------------------------

    /** Changed main sources per module, keyed by the fully qualified name of their top-level class. */
    private static Map<ModuleInfo, Map<String, Path>> changedSources(Context context) {
        Map<ModuleInfo, Map<String, Path>> out = new LinkedHashMap<>();
        for (Path changed : context.changedFiles()) {
            Path file = changed.toAbsolutePath().normalize();
            if (!file.toString().endsWith(".java") || !Files.isRegularFile(file)) {
                continue;
            }
            Optional<ReactorModules.Module> module = context.reactor().moduleOf(file);
            if (module.isEmpty() || !module.get().info().isMainSource(file)) {
                continue;
            }
            ModuleInfo info = module.get().info();
            info.mainSourceRoots().stream().filter(file::startsWith).findFirst().ifPresent(root -> {
                String relative = root.relativize(file).toString().replace('\\', '/');
                String fqn = relative.substring(0, relative.length() - ".java".length()).replace('/', '.');
                out.computeIfAbsent(info, k -> new LinkedHashMap<>()).put(fqn, file);
            });
        }
        return out;
    }

    /** The compiled classes of the given top-level classes, inner classes included; empty when not compiled. */
    private static List<Path> classFiles(ModuleInfo module, Set<String> topLevelClasses) {
        Path classesDir = module.buildTool() == BuildTool.GRADLE
                ? module.moduleRoot().resolve("build/classes/java/main") : module.moduleRoot().resolve("target/classes");
        List<Path> out = new ArrayList<>();
        for (String fqn : topLevelClasses) {
            Path base = classesDir.resolve(fqn.replace('.', '/') + ".class");
            Path dir = base.getParent();
            String simple = base.getFileName().toString().replace(".class", "");
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> {
                    String name = p.getFileName().toString();
                    return name.equals(simple + ".class") || name.startsWith(simple + "$") && name.endsWith(".class");
                }).forEach(out::add);
            } catch (IOException e) {
                LOG.log(Level.FINE, "Could not list " + dir, e);
            }
        }
        return out;
    }

    private static List<String> auxiliaryClasspath(Context context, ModuleInfo module, List<Path> classes) {
        Set<String> entries = new LinkedHashSet<>();
        new ClasspathCacheStore(context.paths().classpathCacheDir()).get(module.moduleRoot())
                .map(ClasspathCache::compileClasspath).ifPresent(entries::addAll);
        Path classesDir = classes.get(0).getParent();
        // The module's own output directory, so that unchanged siblings of the analyzed classes resolve.
        for (Path dir = classesDir; dir != null; dir = dir.getParent()) {
            if (dir.getFileName() != null && (dir.getFileName().toString().equals("classes") || dir.getFileName().toString().equals("main"))) {
                entries.add(dir.toString());
                break;
            }
        }
        return new ArrayList<>(entries);
    }

    private List<Bug> runWithTimeout(List<Path> classes, List<String> aux, Duration timeout) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "javaguard-spotbugs");
            t.setDaemon(true);
            return t;
        });
        Future<List<Bug>> future = executor.submit(() -> runner.run(classes, aux));
        try {
            return future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            LOG.warning("SpotBugs did not finish within the end-of-turn budget; its results are skipped.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LOG.log(Level.WARNING, "SpotBugs failed; its results are skipped.", e.getCause());
        } finally {
            executor.shutdownNow();
        }
        return List.of();
    }

    // --- bug to finding --------------------------------------------------------------------------

    private Optional<Finding> toFinding(Context context, Bug bug, Path source) {
        if (!CATEGORIES.contains(bug.category()) || EXCLUDED.matcher(bug.pattern()).matches()
                || bug.category().equals("PERFORMANCE") && !PERFORMANCE_KEPT.matcher(bug.pattern()).matches()) {
            return Optional.empty();
        }
        Severity severity = severity(bug);
        if (severity == null) {
            return Optional.empty();
        }
        String[] overlap = overlapOf(bug.pattern());
        if (overlap != null && severity == Severity.INFO && !bug.category().equals("MALICIOUS_CODE")) {
            severity = Severity.MAJOR; // A pattern a JavaGuard rule also covers is worth the agent's attention whatever SpotBugs' rank.
        }
        String text;
        try {
            text = Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Optional.empty();
        }
        Optional<String> baseline = context.baselineOf().apply(source);
        if (baseline.isPresent()) {
            LineRanges changed = ChangedLines.between(baseline.get(), text);
            if (bug.line() < 1 || !changed.contains(bug.line())) {
                return Optional.empty(); // Not the session's doing.
            }
        }
        String ruleId = overlap == null ? "SB:" + bug.pattern() : overlap[0];
        if (suppressed(text, bug, ruleId)) {
            return Optional.empty();
        }
        Path relative = context.paths().projectRoot().relativize(source);
        String relativeText = relative.toString().replace('\\', '/');
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("pattern", bug.pattern());
        if (overlap != null) {
            detail.put("alsoReportedBy", "spotbugs");
        }
        String title = overlap != null ? overlap[1] : "SpotBugs: " + bug.pattern();
        String snippet = lineOf(text, bug.line());
        return Optional.of(new Finding(ruleId, title, category(bug), severity, "spotbugs", Optional.of(relative), bug.line(), 0,
                bug.line(), bug.message(),
                "Fix the flagged code (" + bug.pattern() + "). If it is a genuine false positive, add "
                        + "\"// javaguard:ignore " + ruleId + " -- <reason>\".",
                snippet.isEmpty() ? Optional.empty() : Optional.of(snippet), detail,
                Fingerprints.of(ruleId, relativeText, bug.pattern() + " " + bug.className())));
    }

    /** Catalog &sect;5: rank 1-4 CRITICAL, 5-9 MAJOR, 10-14 INFO, 15+ dropped; MALICIOUS_CODE is always INFO. */
    static Severity severity(Bug bug) {
        if (bug.rank() >= 15) {
            return null;
        }
        if (bug.category().equals("MALICIOUS_CODE")) {
            return Severity.INFO;
        }
        return bug.rank() <= 4 ? Severity.CRITICAL : bug.rank() <= 9 ? Severity.MAJOR : Severity.INFO;
    }

    private static Category category(Bug bug) {
        return switch (bug.category()) {
            case "SECURITY", "MALICIOUS_CODE" -> Category.SECURITY;
            case "MT_CORRECTNESS" -> Category.CONCURRENCY;
            case "PERFORMANCE" -> Category.PERFORMANCE;
            default -> Category.RELIABILITY;
        };
    }

    static String[] overlapOf(String pattern) {
        for (Map.Entry<Pattern, String[]> entry : OVERLAPS.entrySet()) {
            if (entry.getKey().matcher(pattern).matches()) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean suppressed(String source, Bug bug, String ruleId) {
        for (SuppressionMarker marker : SuppressionScanner.scan(source)) {
            if (marker.covers(bug.line()) && (marker.ruleId().equals(ruleId) || marker.ruleId().equals("SB:" + bug.pattern()))) {
                return true;
            }
        }
        return false;
    }

    private static String lineOf(String text, int line) {
        if (line < 1) {
            return "";
        }
        String[] lines = text.split("\\R", -1);
        return line <= lines.length ? lines[line - 1].strip() : "";
    }

    private static String outer(String className) {
        int inner = className.indexOf('$');
        return inner < 0 ? className : className.substring(0, inner);
    }
}
