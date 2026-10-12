package com.devmanchego.javaguard.integrity;

import com.devmanchego.javaguard.analysis.RuleInfo;
import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.integrity.BuildFileChecks.Setting;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The diff-based half of the test-integrity rules (specification &sect;11.1, catalog &sect;1.7): the rules
 * that need the baseline as well as the current content. The content-only rules (JG-TST-005, 007, 013, 014)
 * are ordinary AST checks in the {@code analysis} package. Findings here are, by construction, about what the
 * session changed, so they bypass the baseline-findings filter; configuration overrides and suppressions
 * still apply, in the caller.
 *
 * <p>Test-source rules only look at test sources; the build-file rule only at {@code pom.xml} and
 * {@code .mvn/maven.config}.
 */
public final class TestIntegrityEngine {

    private static final Logger LOG = Logger.getLogger(TestIntegrityEngine.class.getName());

    private static RuleInfo diff(String id, String title, Severity severity) {
        return new RuleInfo(id, title, Category.TEST_INTEGRITY, severity, "diff", "F");
    }

    /** Rules produced by this engine, for {@code javaguard rules}. */
    public static final List<RuleInfo> RULES = List.of(
            diff("JG-TST-001", "Test method removed", Severity.CRITICAL),
            diff("JG-TST-002", "Test disabled", Severity.BLOCKER),
            diff("JG-TST-003", "Assertions removed", Severity.CRITICAL),
            diff("JG-TST-004", "Expected value changed", Severity.MAJOR),
            diff("JG-TST-006", "Test body emptied", Severity.CRITICAL),
            diff("JG-TST-008", "Test file deleted", Severity.CRITICAL),
            diff("JG-TST-009", "Tests skipped by build config", Severity.BLOCKER),
            diff("JG-TST-010", "Assumption added", Severity.MAJOR),
            diff("JG-TST-011", "Timeout greatly increased", Severity.INFO),
            diff("JG-TST-012", "Expected-exception test neutralized", Severity.MAJOR));

    private static final Map<String, RuleInfo> BY_ID = new java.util.LinkedHashMap<>();

    static {
        RULES.forEach(r -> BY_ID.put(r.ruleId(), r));
    }

    /** Test class or method sources the rules apply to. */
    public static boolean isTestSource(String relativePath) {
        return TestSourcePaths.isTestSource(relativePath);
    }

    /** True for the files {@link #checkBuildFile} understands. */
    public static boolean isBuildFile(String relativePath) {
        String p = relativePath.replace('\\', '/');
        return p.equals("pom.xml") || p.endsWith("/pom.xml") || p.equals(".mvn/maven.config") || p.endsWith("/.mvn/maven.config")
                || p.endsWith("build.gradle") || p.endsWith("build.gradle.kts");
    }

    /** JG-TST-001, 002, 003, 004, 006, 010, 011, 012 between two versions of a test source. */
    public List<Finding> checkJava(String relativePath, String baseline, String current) {
        if (baseline.isBlank() || current.isBlank()) {
            return List.of(); // A new file has no past to be weaker than.
        }
        Optional<CompilationUnit> before = parse(baseline);
        Optional<CompilationUnit> after = parse(current);
        if (before.isEmpty() || after.isEmpty()) {
            return List.of(); // JG-SYN-001 covers a file that does not parse.
        }
        return new JavaDiffChecks(relativePath, current, this::finding).check(before.get(), after.get());
    }

    /** JG-TST-008: a test source that held tests is gone. */
    public List<Finding> checkDeleted(String relativePath, String baseline) {
        if (baseline.isBlank()) {
            return List.of();
        }
        Optional<CompilationUnit> before = parse(baseline);
        boolean heldTests = before.isEmpty() || TestMethods.index(before.get()).stream().anyMatch(TestMethods.Method::test);
        if (!heldTests) {
            return List.of();
        }
        String name = Path.of(relativePath).getFileName().toString();
        return List.of(finding("JG-TST-008", relativePath, 1, "", "Test source file " + name + " was deleted.",
                "Restore the file; if the tests are obsolete, say so to the user instead of deleting them."));
    }

    /** JG-TST-009 between two versions of {@code pom.xml} or {@code .mvn/maven.config}. */
    public List<Finding> checkBuildFile(String relativePath, String baseline, String current) {
        Optional<Map<String, Setting>> before;
        Optional<Map<String, Setting>> after;
        String normalized = relativePath.replace('\\', '/');
        if (normalized.endsWith("build.gradle") || normalized.endsWith("build.gradle.kts")) {
            before = Optional.of(GradleBuildFileChecks.settings(baseline));
            after = Optional.of(GradleBuildFileChecks.settings(current));
        } else if (normalized.endsWith("maven.config")) {
            before = Optional.of(BuildFileChecks.configSettings(baseline));
            after = Optional.of(BuildFileChecks.configSettings(current));
        } else {
            before = baseline.isBlank() ? Optional.of(Map.of()) : BuildFileChecks.pomSettings(baseline);
            after = BuildFileChecks.pomSettings(current);
        }
        if (before.isEmpty() || after.isEmpty()) {
            return List.of();
        }
        List<String> lines = current.lines().toList();
        List<Finding> out = new ArrayList<>();
        for (Setting setting : after.get().values()) {
            if (before.get().containsKey(setting.key())) {
                continue;
            }
            int line = lineOf(lines, setting.needle());
            out.add(finding("JG-TST-009", relativePath, line, line >= 1 ? lines.get(line - 1).strip() : "",
                    "Tests are weakened by the build configuration: " + setting.description() + " was added.",
                    "Remove it; tests must run and must fail the build when they fail."));
        }
        return out;
    }

    private static int lineOf(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i + 1;
            }
        }
        return 0;
    }

    private Optional<CompilationUnit> parse(String text) {
        try {
            ParseResult<CompilationUnit> result = new JavaParser(new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                    .setAttributeComments(false)).parse(text);
            return result.isSuccessful() ? result.getResult() : Optional.empty();
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Could not parse a test source for the integrity rules.", e);
            return Optional.empty();
        }
    }

    private Finding finding(String ruleId, String relativePath, int line, String snippet, String message, String remediation) {
        RuleInfo info = BY_ID.get(ruleId);
        return new Finding(info.ruleId(), info.title(), info.category(), info.defaultSeverity(), info.engine(),
                Optional.of(Path.of(relativePath)), line, 0, line, message, remediation,
                snippet.isBlank() ? Optional.empty() : Optional.of(snippet), Map.of(),
                Fingerprints.of(info.ruleId(), relativePath, message));
    }
}
