package com.devmanchego.javaguard.analysis.deps;

import com.devmanchego.javaguard.analysis.RuleInfo;
import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * JG-DEP-001, 004 and 005 (specification &sect;11.4, catalog &sect;1.9): what changed between two versions of a build file's
 * dependencies. 001 (INFO) lists a dependency that was not there, 004 (MAJOR) a version that is not pinned (SNAPSHOT, a
 * range, {@code LATEST}/{@code RELEASE}, Gradle's {@code +} and {@code latest.*}) and is new or changed, 005 (MAJOR) a
 * version that went down. A dependency whose version the file leaves to a BOM or parent has nothing to compare and is
 * only ever 001.
 */
public final class DependencyChecks {

    public static final RuleInfo ADDED = new RuleInfo("JG-DEP-001", "Dependency added", Category.DEPENDENCY, Severity.INFO, "diff", "F");
    public static final RuleInfo UNPINNED = new RuleInfo("JG-DEP-004", "Unpinned or snapshot version", Category.DEPENDENCY,
            Severity.MAJOR, "diff", "F");
    public static final RuleInfo DOWNGRADED = new RuleInfo("JG-DEP-005", "Dependency downgraded", Category.DEPENDENCY,
            Severity.MAJOR, "diff", "F");

    private static final Pattern UNPINNED_VERSION = Pattern.compile(
            "(?i).*-SNAPSHOT|LATEST|RELEASE|latest\\.(release|integration)|.*\\+|[\\[(].*,.*[\\])]");

    private DependencyChecks() {
    }

    /** The dependencies in {@code after} that are new or have another version than in {@code before}. */
    public static List<Dependency> addedOrChanged(List<Dependency> before, List<Dependency> after) {
        Map<String, Dependency> old = byKey(before);
        List<Dependency> out = new ArrayList<>();
        for (Dependency now : after) {
            Dependency was = old.get(now.key());
            if (was == null || !was.version().equals(now.version())) {
                out.add(now);
            }
        }
        return out;
    }

    public static List<Finding> check(String relativePath, String baseline, String current) {
        Optional<List<Dependency>> before = baseline.isBlank() ? Optional.of(List.of()) : DependencyParsers.of(relativePath, baseline);
        Optional<List<Dependency>> after = DependencyParsers.of(relativePath, current);
        if (before.isEmpty() || after.isEmpty()) {
            return List.of();
        }
        Map<String, Dependency> old = byKey(before.get());
        List<String> lines = current.lines().toList();
        List<Finding> findings = new ArrayList<>();
        for (Dependency now : after.get()) {
            Dependency was = old.get(now.key());
            int line = lineOf(lines, now);
            if (was == null) {
                findings.add(finding(ADDED, relativePath, line, lines, now, "Dependency " + now.coordinates() + " was added"
                        + (now.scope().isEmpty() ? "." : " (" + now.scope() + ")."),
                        "Check that it is needed and that the coordinates are real; prefer a version the build already manages."));
            }
            if (!now.version().isEmpty() && UNPINNED_VERSION.matcher(now.version()).matches()
                    && (was == null || !was.version().equals(now.version()))) {
                findings.add(finding(UNPINNED, relativePath, line, lines, now, "Dependency " + now.key() + " uses the unpinned version "
                        + now.version() + ".", "Pin an exact released version so builds are reproducible."));
            }
            if (was != null && !was.version().isEmpty() && !now.version().isEmpty() && !was.version().equals(now.version())
                    && VersionComparator.compare(now.version(), was.version()) < 0) {
                findings.add(finding(DOWNGRADED, relativePath, line, lines, now, "Dependency " + now.key() + " went from "
                        + was.version() + " down to " + now.version() + ".",
                        "Keep the newer version unless the downgrade is deliberate; downgrades often reintroduce fixed bugs and vulnerabilities."));
            }
        }
        return findings;
    }

    private static Map<String, Dependency> byKey(List<Dependency> dependencies) {
        Map<String, Dependency> out = new LinkedHashMap<>();
        dependencies.forEach(d -> out.putIfAbsent(d.key(), d));
        return out;
    }

    private static Finding finding(RuleInfo rule, String file, int line, List<String> lines, Dependency dependency, String message,
                                   String remediation) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("artifact", dependency.coordinates());
        String snippet = line >= 1 && line <= lines.size() ? lines.get(line - 1).strip() : "";
        return new Finding(rule.ruleId(), rule.title(), rule.category(), rule.defaultSeverity(), rule.engine(), Optional.of(Path.of(file)),
                Math.max(line, 0), 0, Math.max(line, 0), message, remediation, snippet.isEmpty() ? Optional.empty() : Optional.of(snippet),
                detail, Fingerprints.of(rule.ruleId(), file.replace('\\', '/'), dependency.coordinates()));
    }

    /** First line that names the artifact (and, for Maven, is its {@code <artifactId>}); 0 when none does. */
    static int lineOf(List<String> lines, Dependency dependency) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains(dependency.key()) || line.contains("<artifactId>" + dependency.artifactId() + "</artifactId>")
                    || line.contains("\"" + dependency.artifactId() + "\"")) {
                return i + 1;
            }
        }
        return 0;
    }
}
