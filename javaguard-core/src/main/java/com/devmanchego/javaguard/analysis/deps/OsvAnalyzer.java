package com.devmanchego.javaguard.analysis.deps;

import com.devmanchego.javaguard.analysis.RuleInfo;
import com.devmanchego.javaguard.analysis.deps.OsvClient.Vulnerability;
import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.gate.DeepAnalyzer;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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
 * JG-DEP-002 (specification &sect;11.4 step 5): known vulnerabilities of the dependency versions that a changed build file
 * added or changed, from OSV. Only coordinates with a concrete version are asked about (a version left to a BOM or
 * parent is not known here), and only the ones the session touched, so existing debt in the build is not reported.
 * Severity from the CVSS base score: 9 and up CRITICAL, 7 and up MAJOR, else INFO. At most five advisories per
 * dependency are listed, worst first. A lookup that fails (no network, timeout, malformed answer) is one INFRASTRUCTURE
 * finding, which the gate turns into a note: never a block.
 */
public final class OsvAnalyzer implements DeepAnalyzer {

    /** Catalog metadata for JG-DEP-002 (the severity is decided per advisory). */
    public static final RuleInfo INFO = new RuleInfo("JG-DEP-002", "Known vulnerability", Category.DEPENDENCY, Severity.MAJOR, "osv", "E");

    private static final Logger LOG = Logger.getLogger(OsvAnalyzer.class.getName());
    private static final int MAX_PER_DEPENDENCY = 5;

    private final Function<Duration, OsvClient> clients;

    /** The public OSV API. */
    public OsvAnalyzer() {
        this(timeout -> new HttpOsvClient(HttpOsvClient.PUBLIC_API, timeout));
    }

    /** {@code clients} makes the client for a given time limit (tests point it at recorded answers). */
    public OsvAnalyzer(Function<Duration, OsvClient> clients) {
        this.clients = clients;
    }

    @Override
    public List<Finding> analyze(Context context) {
        if (!context.config().deps().osv().enabled()) {
            return List.of();
        }
        Map<Path, List<Dependency>> candidates = new LinkedHashMap<>();
        for (Path changed : context.changedFiles()) {
            Path file = changed.toAbsolutePath().normalize();
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String current = Files.readString(file, StandardCharsets.UTF_8);
                List<Dependency> after = DependencyParsers.of(file.toString(), current).orElse(List.of());
                List<Dependency> before = context.baselineOf().apply(file).flatMap(text -> DependencyParsers.of(file.toString(), text))
                        .orElse(List.of());
                List<Dependency> touched = DependencyChecks.addedOrChanged(before, after).stream().filter(OsvAnalyzer::concrete).toList();
                if (!touched.isEmpty()) {
                    candidates.put(file, touched);
                }
            } catch (IOException e) {
                LOG.log(Level.FINE, "Could not read " + file, e);
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<Dependency> distinct = new LinkedHashSet<>();
        candidates.values().forEach(distinct::addAll);
        List<Vulnerability> vulnerabilities;
        try {
            Duration timeout = Duration.ofSeconds(context.config().deps().osv().timeoutSeconds());
            vulnerabilities = clients.apply(timeout).query(new ArrayList<>(distinct));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "The OSV lookup failed.", e);
            return List.of(unavailable(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<Path, List<Dependency>> entry : candidates.entrySet()) {
            String relative = context.paths().projectRoot().relativize(entry.getKey()).toString().replace('\\', '/');
            List<String> lines = lines(entry.getKey());
            for (Dependency dependency : entry.getValue()) {
                List<Vulnerability> own = vulnerabilities.stream().filter(v -> v.dependency().equals(dependency))
                        .sorted(Comparator.comparingDouble((Vulnerability v) -> v.score().orElse(-1)).reversed()).toList();
                for (Vulnerability vulnerability : own.stream().limit(MAX_PER_DEPENDENCY).toList()) {
                    findings.add(finding(relative, lines, vulnerability, own.size()));
                }
            }
        }
        return findings;
    }

    static Severity severity(Vulnerability vulnerability) {
        double score = vulnerability.score().orElse(0);
        return score >= 9 ? Severity.CRITICAL : score >= 7 ? Severity.MAJOR : Severity.INFO;
    }

    private static Finding finding(String file, List<String> lines, Vulnerability v, int total) {
        int line = DependencyChecks.lineOf(lines, v.dependency());
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("artifact", v.dependency().coordinates());
        detail.put("advisory", v.id());
        v.score().ifPresent(score -> detail.put("cvss", String.valueOf(score)));
        String message = v.dependency().coordinates() + " has a known vulnerability, " + v.id()
                + (v.summary().isBlank() ? "" : ": " + v.summary()) + (v.score().isPresent() ? " (CVSS " + v.score().getAsDouble() + ")" : "")
                + (total > MAX_PER_DEPENDENCY ? ". " + (total - MAX_PER_DEPENDENCY) + " more advisories affect this version." : ".");
        String snippet = line >= 1 && line <= lines.size() ? lines.get(line - 1).strip() : "";
        return new Finding(INFO.ruleId(), INFO.title(), INFO.category(), severity(v), INFO.engine(), Optional.of(Path.of(file)),
                Math.max(line, 0), 0, Math.max(line, 0), message,
                "Use a version that is not affected (see https://osv.dev/vulnerability/" + v.id() + "), or pick another library.",
                snippet.isEmpty() ? Optional.empty() : Optional.of(snippet), detail,
                Fingerprints.of(INFO.ruleId(), file, v.dependency().coordinates() + " " + v.id()));
    }

    private static Finding unavailable(String reason) {
        String message = "The vulnerability database (OSV) could not be queried (" + (reason == null ? "unknown error" : reason)
                + "); the new dependencies were not checked.";
        return new Finding("OSV", "Vulnerability lookup unavailable", Category.INFRASTRUCTURE, Severity.INFO, "osv", Optional.empty(), 0, 0, 0,
                message, "Nothing to do: the next build-file change is checked again.", Optional.empty(), Map.of(),
                Fingerprints.of("OSV", "", "unavailable"));
    }

    /** A version that starts with a digit: not empty, not an unresolved property, not a range or a dynamic marker. */
    private static boolean concrete(Dependency dependency) {
        String version = dependency.version();
        return !version.isEmpty() && Character.isDigit(version.charAt(0)) && !version.contains("${") && !version.endsWith("+");
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }
}
