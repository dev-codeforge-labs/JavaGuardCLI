package com.devmanchego.javaguard.change;

import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specification section 9.3 end to end, on the {@code legacy-dirty} fixture:
 * pre-existing problems in legacy code must stay silent, new ones must not.
 * The analyzer is a stand-in (real rules arrive in Phase 5); scoping does not
 * care what the rules are, only that they are deterministic.
 */
class ScopedAnalysisTest {

    private static final String REL = "legacy-src/main/java/com/acme/legacy/Old.java";
    private static final Pattern SWALLOWED = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*(?://[^\\n]*\\s*)*}");

    @TempDir
    Path cacheDir;

    private final AtomicInteger analyzerCalls = new AtomicInteger();

    /** Stand-in rules: every FileInputStream "leaks", every empty catch "swallows". */
    private List<Finding> analyze(String rel, String content) {
        analyzerCalls.incrementAndGet();
        List<Finding> out = new ArrayList<>();
        List<String> lines = content.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("new FileInputStream(")) {
                out.add(finding("JG-REL-003", rel, i + 1, "FileInputStream opened here is never closed."));
            }
        }
        Matcher m = SWALLOWED.matcher(content);
        while (m.find()) {
            String prefix = content.substring(0, m.start());
            int line = (int) prefix.lines().count() + (prefix.isEmpty() || prefix.endsWith("\n") ? 1 : 0);
            out.add(finding("JG-REL-001", rel, line, "Exception swallowed."));
        }
        return out;
    }

    private static Finding finding(String ruleId, String rel, int line, String message) {
        return new Finding(ruleId, ruleId, Category.RELIABILITY, Severity.MAJOR, "test", Optional.of(Path.of(rel)),
                line, 1, line, message, "fix it", Optional.empty(), Map.of(), Fingerprints.of(ruleId, rel, message));
    }

    private ScopedAnalysis scoped(ConfigFindingPolicy policy, boolean allowAgentAdded) {
        return new ScopedAnalysis(new BaselineFindingsCache(cacheDir), policy, allowAgentAdded);
    }

    private static String legacyOld() throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/legacy-dirty").resolve(REL), StandardCharsets.UTF_8);
    }

    /** The agent adds a new method with the same two mistakes, and a comment block at the top that shifts every line. */
    private static String editedByAgent(String original) {
        String shifted = original.replace("public class Old {", "/*\n * Reviewed.\n */\npublic class Old {");
        int lastBrace = shifted.lastIndexOf('}');
        return shifted.substring(0, lastBrace) + """

                    public void readAll(String path) {
                        try {
                            FileInputStream in = new FileInputStream(path);
                            in.readAllBytes();
                        } catch (IOException e) {
                        }
                    }
                }
                """;
    }

    @Test
    void legacyProblemsStaySilentAndOnlyTheNewMethodIsReported() throws IOException {
        String baseline = legacyOld();
        String current = editedByAgent(baseline);
        assertThat(analyze(REL, baseline)).hasSize(2); // the fixture really is dirty
        assertThat(analyze(REL, current)).hasSize(4);

        ScopedAnalysis.Result result = scoped(ConfigFindingPolicy.none(), true)
                .run(REL, baseline, current, this::analyze, "test-v1");

        assertThat(result.reported()).extracting(Finding::ruleId).containsExactlyInAnyOrder("JG-REL-003", "JG-REL-001");
        int newMethodStart = (int) current.substring(0, current.indexOf("readAll")).lines().count();
        assertThat(result.reported()).allSatisfy(f -> assertThat(f.line()).isGreaterThanOrEqualTo(newMethodStart));
    }

    @Test
    void untouchedFileReportsNothingEvenThoughItIsDirty() throws IOException {
        String baseline = legacyOld();

        assertThat(scoped(ConfigFindingPolicy.none(), true).run(REL, baseline, baseline, this::analyze, "test-v1").reported())
                .isEmpty();
    }

    @Test
    void removingASuppressionSurfacesTheFindingItHid() {
        String baseline = "class A {\n  void m() {\n    try { x(); }\n    // javaguard:ignore JG-REL-001 -- legacy callback\n    catch (Exception e) { }\n  }\n}\n";
        String current = baseline.replace("    // javaguard:ignore JG-REL-001 -- legacy callback\n", "");

        ScopedAnalysis.Result result = scoped(ConfigFindingPolicy.none(), true)
                .run("src/A.java", baseline, current, this::analyze, "test-v1");

        assertThat(result.reported()).extracting(Finding::ruleId).containsExactly("JG-REL-001");
    }

    @Test
    void newSuppressionWithoutReasonHidesTheFindingButIsReportedAndListed() {
        String baseline = "class A {\n  void m() {\n    try { x(); } catch (Exception e) { }\n  }\n}\n";
        String current = baseline.replace("    try {", "    // javaguard:ignore JG-REL-001\n    try {");

        ScopedAnalysis.Result result = scoped(ConfigFindingPolicy.none(), true)
                .run("src/A.java", baseline, current, this::analyze, "test-v1");

        assertThat(result.reported()).extracting(Finding::ruleId).containsExactly(SuppressionFilter.MISSING_REASON_RULE);
        assertThat(result.newSuppressions()).singleElement().satisfies(s -> assertThat(s.ruleId()).isEqualTo("JG-REL-001"));
    }

    @Test
    void newSuppressionIsIgnoredAndBlocksWhenAgentAddedSuppressionsAreDisallowed() {
        String baseline = "class A {\n  void m() {\n    try { x(); } catch (Exception e) { }\n  }\n}\n";
        String current = baseline.replace("  void m() {\n    try { x(); } catch (Exception e) { }",
                "  void m() {\n    try { x(); } catch (Exception e) { } // javaguard:ignore JG-REL-001 -- trust me\n"
                        + "    try { y(); } catch (Exception e) { }");

        ScopedAnalysis.Result result = scoped(ConfigFindingPolicy.none(), false)
                .run("src/A.java", baseline, current, this::analyze, "test-v1");

        assertThat(result.reported()).extracting(Finding::ruleId)
                .containsExactlyInAnyOrder("JG-REL-001", SuppressionFilter.AGENT_ADDED_RULE);
    }

    @Test
    void baselineIsAnalyzedOnceThenServedFromTheCache() throws IOException {
        String baseline = legacyOld();
        String current = editedByAgent(baseline);
        ScopedAnalysis analysis = scoped(ConfigFindingPolicy.none(), true);

        analysis.run(REL, baseline, current, this::analyze, "test-v1");
        analysis.run(REL, baseline, current + "\n", this::analyze, "test-v1");

        assertThat(analyzerCalls.get()).as("1 baseline + 2 current").isEqualTo(3);
    }

    @Test
    void aDifferentAnalyzerKeyDoesNotReuseTheCache() throws IOException {
        String baseline = legacyOld();
        ScopedAnalysis analysis = scoped(ConfigFindingPolicy.none(), true);

        analysis.run(REL, baseline, baseline, this::analyze, "test-v1");
        analysis.run(REL, baseline, baseline, this::analyze, "test-v2");

        assertThat(analyzerCalls.get()).isEqualTo(4);
    }

    @Test
    void globallyExcludedFilesAreNotAnalyzedAtAll() {
        ConfigFindingPolicy policy = new ConfigFindingPolicy(List.of("**/generated/**"), Map.of());

        ScopedAnalysis.Result result = scoped(policy, true)
                .run("src/generated/A.java", "", "class A { void m(){ try{}catch(Exception e){} } }", this::analyze, "k");

        assertThat(result.reported()).isEmpty();
        assertThat(analyzerCalls.get()).isZero();
    }

    @Test
    void disabledRuleAndSeverityOverrideComeFromConfiguration() {
        Map<String, JavaGuardConfig.RuleOverride> rules = Map.of(
                "JG-REL-003", new JavaGuardConfig.RuleOverride(Optional.of(false), Optional.empty(), List.of(), Map.of()),
                "JG-REL-001", new JavaGuardConfig.RuleOverride(Optional.empty(), Optional.of(Severity.INFO), List.of(), Map.of()));
        String current = "class A {\n  void m() throws Exception {\n    new FileInputStream(p);\n    try { x(); } catch (Exception e) { }\n  }\n}\n";

        ScopedAnalysis.Result result = scoped(new ConfigFindingPolicy(List.of(), rules), true)
                .run("src/A.java", "", current, this::analyze, "k");

        assertThat(result.reported()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("JG-REL-001");
            assertThat(f.severity()).isEqualTo(Severity.INFO);
        });
    }
}
