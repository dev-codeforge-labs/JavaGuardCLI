package com.devmanchego.javaguard.analysis.spotbugs;

import com.devmanchego.javaguard.analysis.spotbugs.SpotBugsRunner.Bug;
import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.gate.DeepAnalyzer;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.ReactorModules;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 18: a seeded SQL injection is found by FindSecBugs and reported under JG-SEC-001; only what the session changed
 * is reported; suppressions, exclusions and the rank-to-severity table of catalog section 5 hold.
 */
class SpotBugsAnalyzerTest {

    private static final String REL = "src/main/java/com/acme/UserDao.java";

    @TempDir
    Path root;

    private JavaGuardPaths paths;
    private JavaGuardConfig config;
    private Path source;

    @BeforeEach
    void module() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                + "<groupId>g</groupId><artifactId>a</artifactId><version>1</version></project>");
        paths = new JavaGuardPaths(root);
        paths.ensureLayout();
        config = ConfigLoader.load(ConfigSources.standard(root, Optional.empty(), root.resolve("home"), Map.of())).config();
        source = root.resolve(REL);
    }

    /** Writes the source and compiles it into {@code target/classes}, as the end-of-turn build would have. */
    private void build(String text) throws IOException {
        Files.createDirectories(source.getParent());
        Files.writeString(source, text);
        Path classes = Files.createDirectories(root.resolve("target/classes"));
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "--release", "17", "-d", classes.toString(), source.toString());
        assertThat(result).isZero();
    }

    private List<Finding> analyze(SpotBugsAnalyzer analyzer, String baseline) {
        return analyzer.analyze(new DeepAnalyzer.Context(List.of(source), ReactorModules.load(root), Duration.ofMinutes(5),
                file -> Optional.ofNullable(baseline), config, paths));
    }

    @Test
    void aNewFileWithASqlInjectionIsReportedUnderJgSec001() throws Exception {
        build(SpotBugsRunnerTest.SQL_INJECTION);

        List<Finding> findings = analyze(new SpotBugsAnalyzer(), null);

        assertThat(findings).filteredOn(f -> f.ruleId().equals("JG-SEC-001")).hasSize(1); // both SQL patterns, one problem
        assertThat(findings).anySatisfy(f -> {
            assertThat(f.ruleId()).isEqualTo("JG-SEC-001");
            assertThat(f.title()).isEqualTo("SQL built by concatenation");
            assertThat(f.category()).isEqualTo(Category.SECURITY);
            assertThat(f.engine()).isEqualTo("spotbugs");
            assertThat(f.file()).contains(Path.of(REL));
            assertThat(f.line()).isEqualTo(11);
            assertThat(f.detail()).containsEntry("alsoReportedBy", "spotbugs");
            assertThat(f.detail().get("pattern")).contains("SQL_INJECTION_JDBC").contains("SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE");
            assertThat(f.snippet()).hasValueSatisfying(s -> assertThat(s).contains("executeQuery"));
            assertThat(f.severity()).isIn(Severity.CRITICAL, Severity.MAJOR);
        });
    }

    /** A runner that reports exactly the given bugs, all in {@code com.acme.UserDao} line 11 (the executeQuery line). */
    private static SpotBugsRunner reporting(Bug... bugs) {
        return new SpotBugsRunner() {
            @Override
            public List<Bug> run(List<Path> classFiles, List<String> auxiliaryClasspath) {
                return List.of(bugs);
            }
        };
    }

    private static Bug sqlBug(String pattern, int rank) {
        return new Bug(pattern, "SECURITY", rank, "com.acme.UserDao", 11, "m");
    }

    /**
     * Phase 27 decision, measured on fineract (388 SQL findings, none with user input in the sample read): FindSecBugs'
     * own rank decides. A flow it is sure about (rank 1-4) blocks at the end of the turn; "unknown" flows, which is what
     * {@code "select " + mapper.schema() + " where id = ?"} is, only advise. The post-edit syntactic rule is the BLOCKER.
     */
    @Test
    void sqlInjectionPatternsFollowTheirRankAndAreNeverBelowMajor() throws Exception {
        build(SpotBugsRunnerTest.SQL_INJECTION);

        for (String pattern : List.of("SQL_INJECTION_JDBC", "SQL_INJECTION_SPRING_JDBC", "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE")) {
            assertThat(analyzeWith(reporting(sqlBug(pattern, 3)))).singleElement()
                    .satisfies(f -> assertThat(f.severity()).as(pattern + " rank 3").isEqualTo(Severity.CRITICAL));
            assertThat(analyzeWith(reporting(sqlBug(pattern, 7)))).singleElement()
                    .satisfies(f -> assertThat(f.severity()).as(pattern + " rank 7").isEqualTo(Severity.MAJOR));
            assertThat(analyzeWith(reporting(sqlBug(pattern, 11)))).singleElement()
                    .satisfies(f -> assertThat(f.severity()).as(pattern + " rank 11 is floored").isEqualTo(Severity.MAJOR));
        }
        // Both kinds on one line: one finding, the most severe, both patterns listed.
        assertThat(analyzeWith(reporting(sqlBug("SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE", 11), sqlBug("SQL_INJECTION_JDBC", 4))))
                .singleElement().satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
                    assertThat(f.detail().get("pattern")).contains("SQL_INJECTION_JDBC").contains("SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE");
                });
    }

    @Test
    void aSqlInjectionRankedBelowTheThresholdStaysDropped() throws Exception {
        build(SpotBugsRunnerTest.SQL_INJECTION);

        assertThat(analyzeWith(reporting(sqlBug("SQL_INJECTION_JDBC", 16)))).isEmpty();
    }

    private List<Finding> analyzeWith(SpotBugsRunner runner) {
        return analyze(new SpotBugsAnalyzer(runner), null);
    }

    @Test
    void onlyBugsOnLinesTheSessionChangedAreReported() throws Exception {
        build(SpotBugsRunnerTest.SQL_INJECTION);

        // The baseline already had the very same code: nothing here is the session's doing.
        assertThat(analyze(new SpotBugsAnalyzer(), SpotBugsRunnerTest.SQL_INJECTION)).isEmpty();

        // The session changed the injected line itself.
        String baseline = SpotBugsRunnerTest.SQL_INJECTION.replace("name = '\" + name + \"'", "name = 'x'");
        assertThat(analyze(new SpotBugsAnalyzer(), baseline)).anyMatch(f -> f.ruleId().equals("JG-SEC-001"));
    }

    @Test
    void aJavaguardIgnoreWithTheJgIdOrTheSpotBugsPatternSuppresses() throws Exception {
        build(SpotBugsRunnerTest.SQL_INJECTION.replace("        return statement.executeQuery",
                "        // javaguard:ignore JG-SEC-001 -- the name is validated by the caller\n        return statement.executeQuery"));

        assertThat(analyze(new SpotBugsAnalyzer(), null)).noneMatch(f -> f.ruleId().equals("JG-SEC-001"));
    }

    @Test
    void disabledByConfigNothingRuns() throws Exception {
        Files.writeString(root.resolve("javaguard.yml"), "spotbugs:\n  enabled: false\n");
        config = ConfigLoader.load(ConfigSources.standard(root, Optional.empty(), root.resolve("home"), Map.of())).config();
        build(SpotBugsRunnerTest.SQL_INJECTION);

        assertThat(analyze(new SpotBugsAnalyzer(), null)).isEmpty();
    }

    @Test
    void sourcesThatWereNotCompiledAreSkippedAndTestSourcesAreNotAnalyzed() throws Exception {
        Files.createDirectories(source.getParent());
        Files.writeString(source, SpotBugsRunnerTest.SQL_INJECTION); // no class files
        assertThat(analyze(new SpotBugsAnalyzer(), null)).isEmpty();

        Path test = root.resolve("src/test/java/com/acme/UserDaoTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, "package com.acme; class UserDaoTest {}");
        List<Finding> findings = new SpotBugsAnalyzer().analyze(new DeepAnalyzer.Context(List.of(test), ReactorModules.load(root),
                Duration.ofMinutes(5), file -> Optional.empty(), config, paths));
        assertThat(findings).isEmpty();
    }

    @Test
    void aRunnerThatFailsOrHangsCostsNothing() throws Exception {
        build(SpotBugsRunnerTest.SQL_INJECTION);
        SpotBugsRunner failing = new SpotBugsRunner() {
            @Override
            public List<Bug> run(List<Path> classFiles, List<String> auxiliaryClasspath) throws IOException {
                throw new IOException("SpotBugs exploded");
            }
        };
        assertThat(analyze(new SpotBugsAnalyzer(failing), null)).isEmpty();

        SpotBugsRunner hanging = new SpotBugsRunner() {
            @Override
            public List<Bug> run(List<Path> classFiles, List<String> auxiliaryClasspath) throws InterruptedException {
                Thread.sleep(60_000);
                return List.of();
            }
        };
        long start = System.nanoTime();
        List<Finding> result = new SpotBugsAnalyzer(hanging, Duration.ofSeconds(1), Duration.ZERO).analyze(new DeepAnalyzer.Context(List.of(source),
                ReactorModules.load(root), Duration.ofSeconds(2), file -> Optional.empty(), config, paths));
        assertThat(result).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void tooLittleBudgetSkipsTheAnalysis() throws Exception {
        build(SpotBugsRunnerTest.SQL_INJECTION);

        assertThat(new SpotBugsAnalyzer().analyze(new DeepAnalyzer.Context(List.of(source), ReactorModules.load(root), Duration.ofSeconds(5),
                file -> Optional.empty(), config, paths))).isEmpty();
    }

    // --- the catalog's tables ----------------------------------------------------------------------

    private static Bug bug(String pattern, String category, int rank) {
        return new Bug(pattern, category, rank, "com.acme.X", 3, "m");
    }

    @Test
    void ranksMapToSeveritiesPerTheCatalog() {
        assertThat(SpotBugsAnalyzer.severity(bug("P", "CORRECTNESS", 1))).isEqualTo(Severity.CRITICAL);
        assertThat(SpotBugsAnalyzer.severity(bug("P", "CORRECTNESS", 4))).isEqualTo(Severity.CRITICAL);
        assertThat(SpotBugsAnalyzer.severity(bug("P", "SECURITY", 5))).isEqualTo(Severity.MAJOR);
        assertThat(SpotBugsAnalyzer.severity(bug("P", "SECURITY", 9))).isEqualTo(Severity.MAJOR);
        assertThat(SpotBugsAnalyzer.severity(bug("P", "MT_CORRECTNESS", 10))).isEqualTo(Severity.INFO);
        assertThat(SpotBugsAnalyzer.severity(bug("P", "MT_CORRECTNESS", 14))).isEqualTo(Severity.INFO);
        assertThat(SpotBugsAnalyzer.severity(bug("P", "CORRECTNESS", 15))).isNull();
        assertThat(SpotBugsAnalyzer.severity(bug("P", "MALICIOUS_CODE", 2))).isEqualTo(Severity.INFO);
    }

    @Test
    void overlappingPatternsMapToTheirJgRules() {
        assertThat(SpotBugsAnalyzer.overlapOf("SQL_INJECTION_JDBC")[0]).isEqualTo("JG-SEC-001");
        assertThat(SpotBugsAnalyzer.overlapOf("COMMAND_INJECTION")[0]).isEqualTo("JG-SEC-002");
        assertThat(SpotBugsAnalyzer.overlapOf("XXE_DOCUMENT")[0]).isEqualTo("JG-SEC-004");
        assertThat(SpotBugsAnalyzer.overlapOf("HARD_CODE_PASSWORD")[0]).isEqualTo("JG-SEC-008");
        assertThat(SpotBugsAnalyzer.overlapOf("NP_NULL_ON_SOME_PATH")).isNull();
    }
}
