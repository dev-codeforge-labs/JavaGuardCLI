package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.build.BuildResult;
import com.devmanchego.javaguard.build.BuildRunner;
import com.devmanchego.javaguard.change.ChangeTracker;
import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.change.GitClient;
import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.ChangeType;
import com.devmanchego.javaguard.model.FileChange;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.HookEvent;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.project.ReactorModules;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import com.devmanchego.javaguard.testing.TestSelection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 9: the END_OF_TURN gate's orchestration (specification &sect;10.3) with a scripted build
 * runner and a scripted working tree, on a copy of the real {@code maven-simple} fixture. The real Maven is
 * exercised by {@code MavenBuildRunnerTest} and by the hook end-to-end test in the cli module.
 */
class EndOfTurnGateTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures").toAbsolutePath().normalize();
    private static final String APP = "src/main/java/com/acme/simple/App.java";
    private static final String APP_TEST = "src/test/java/com/acme/simple/AppTest.java";
    private static final String CLEAN = """
            package com.acme.simple;

            public class App {
                public String greet(String name) {
                    return "Hello, " + name + "!";
                }
            }
            """;

    /** Records what the gate asks of the build tool and answers from scripted results. */
    private static final class FakeRunner implements BuildRunner {
        final List<Collection<Path>> compileCalls = new ArrayList<>();
        final List<TestSelection> testCalls = new ArrayList<>();
        BuildResult compileResult = ok();
        BuildResult testResult = ok();

        @Override
        public BuildResult testCompile(Collection<Path> moduleRoots, Duration timeout) {
            compileCalls.add(List.copyOf(moduleRoots));
            return compileResult;
        }

        @Override
        public BuildResult runTests(TestSelection selection, Duration timeout) {
            testCalls.add(selection);
            return testResult;
        }
    }

    @TempDir
    Path home;

    @TempDir
    Path project;

    private JavaGuardPaths paths;
    private ChangeTracker tracker;
    private final FakeRunner runner = new FakeRunner();
    private final Set<Path> dirty = new HashSet<>();
    private final AtomicInteger refreshes = new AtomicInteger();
    private final List<DeepAnalyzer> deep = new ArrayList<>();
    private Optional<BuildRunner> runnerSupplied;

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void copyFixture() throws IOException {
        try (Stream<Path> walk = Files.walk(FIXTURES.resolve("maven-simple"))) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path target = project.resolve(FIXTURES.resolve("maven-simple").relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else if (!("/" + FIXTURES.resolve("maven-simple").relativize(source)).replace('\\', '/').contains("/target/")) {
                    Files.copy(source, target);
                }
            }
        }
        paths = new JavaGuardPaths(project);
        paths.ensureLayout();
        tracker = new ChangeTracker(paths, GitClient.none(), CLOCK);
        runnerSupplied = Optional.of(runner);
    }

    private static BuildResult ok() {
        return new BuildResult(true, true, 0, List.of(), List.of(), Optional.empty(), Duration.ZERO);
    }

    private static BuildResult failed(Finding... findings) {
        return new BuildResult(false, true, 0, List.of(findings), List.of(), Optional.empty(), Duration.ZERO);
    }

    private JavaGuardConfig config(String... envPairs) throws Exception {
        Map<String, String> env = new java.util.HashMap<>();
        for (int i = 0; i < envPairs.length; i += 2) {
            env.put(envPairs[i], envPairs[i + 1]);
        }
        return ConfigLoader.load(ConfigSources.standard(project, Optional.empty(), home, env)).config();
    }

    private EndOfTurnGate gate(JavaGuardConfig config) {
        Function<ReactorModules, Optional<BuildRunner>> runners = reactor -> runnerSupplied;
        return new EndOfTurnGate(paths, config, tracker, () -> Optional.of(Set.copyOf(dirty)), GitClient.none(), runners,
                (module, remaining) -> refreshes.incrementAndGet(), deep, CLOCK);
    }

    private GateResult endOfTurn(JavaGuardConfig config) {
        return gate(config).run(event(GateKind.END_OF_TURN, List.of()));
    }

    private static HookEvent event(GateKind gate, List<FileChange> changes) {
        return new HookEvent(AgentKind.CLAUDE, gate, "s1", Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath(),
                Optional.empty(), gate == GateKind.END_OF_TURN ? "" : "Write", changes, false, Optional.empty(),
                CLOCK.instant());
    }

    private Path write(String relative, String content) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /** An agent edit as POST_EDIT sees it: writes the file, records it in the ledger, runs the fast gate. */
    private GateResult agentEdit(String relative, String content, JavaGuardConfig config) throws IOException {
        Path file = write(relative, content);
        PostEditGate gate = new PostEditGate(paths, config, tracker, CLOCK, () -> Optional.of(Set.copyOf(dirty)));
        return gate.run(event(GateKind.POST_EDIT, List.of(FileChange.rewrite(file, ChangeType.ADD))));
    }

    private static Finding finding(String ruleId, Category category, Severity severity, String file, String message,
                                   Map<String, String> detail) {
        return new Finding(ruleId, "t", category, severity, "maven", Optional.of(Path.of(file)), 1, 1, 1, message, "fix",
                Optional.empty(), detail, Fingerprints.of(ruleId, file, message));
    }

    private static Finding javac() {
        return finding("JAVAC", Category.COMPILATION, Severity.CRITICAL, APP, "cannot find symbol", Map.of());
    }

    private static Finding testFailure(String classification, Severity severity) {
        return finding("TEST", Category.TEST_FAILURE, severity, APP_TEST, "AppTest.t failed: boom",
                Map.of("classification", classification, "test", "com.acme.simple.AppTest#t"));
    }

    // --- changed set -----------------------------------------------------------------------------

    @Test
    void withNothingChangedItPassesFastWithoutTouchingTheBuildTool() throws Exception {
        long start = System.nanoTime();

        GateResult result = endOfTurn(config());

        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
        assertThat(runner.compileCalls).isEmpty();
        assertThat(runner.testCalls).isEmpty();
    }

    @Test
    void anEditedFileIsCompiledWithItsModuleAndItsAffectedTestsAreRun() throws Exception {
        agentEdit(APP, CLEAN, config());

        GateResult result = endOfTurn(config());

        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        assertThat(runner.compileCalls).hasSize(1);
        assertThat(runner.compileCalls.get(0)).containsExactly(project);
        assertThat(runner.testCalls).hasSize(1);
        assertThat(runner.testCalls.get(0).classes()).containsExactly("com.acme.simple.AppTest");
    }

    @Test
    void aSecondRunWithNothingNewDoesNotBuildAgain() throws Exception {
        agentEdit(APP, CLEAN, config());
        endOfTurn(config());
        runner.compileCalls.clear();
        runner.testCalls.clear();

        GateResult again = endOfTurn(config());

        assertThat(again.verdict()).isEqualTo(Verdict.PASS);
        assertThat(runner.compileCalls).isEmpty();
    }

    @Test
    void aBuildFileChangeTriggersACompile() throws Exception {
        agentEdit(APP, CLEAN, config());
        endOfTurn(config());
        runner.compileCalls.clear();
        Path pom = project.resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom).replace("</project>", "<!-- edit --></project>"));
        dirty.add(pom);

        endOfTurn(config());

        assertThat(runner.compileCalls).hasSize(1);
    }

    @Test
    void aShellEditOutsideTheLedgerIsDetectedThroughTheWorkingTree() throws Exception {
        agentEdit(APP, CLEAN, config());
        endOfTurn(config());
        runner.compileCalls.clear();
        runner.testCalls.clear();
        Path shell = write("src/main/java/com/acme/simple/FromShell.java", "package com.acme.simple;\nclass FromShell {}\n");
        dirty.add(shell);

        GateResult result = endOfTurn(config());

        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        assertThat(runner.compileCalls).as("the shell-edited file's module is built").hasSize(1);
    }

    @Test
    void aShellEditedFileIsAlsoCheckedByTheFileLocalRulesBecausePostEditNeverSawIt() throws Exception {
        agentEdit(APP, CLEAN, config());
        endOfTurn(config());
        Path shell = write("src/main/java/com/acme/simple/Dao.java", """
                package com.acme.simple;

                import java.sql.Connection;
                import java.sql.PreparedStatement;
                import java.sql.SQLException;

                class Dao {
                    PreparedStatement run(Connection c, String id) throws SQLException {
                        return c.prepareStatement("SELECT * FROM t WHERE id = " + id);
                    }
                }
                """);
        dirty.add(shell);

        GateResult result = endOfTurn(config());

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).extracting(Finding::ruleId).contains("JG-SEC-001");
    }

    @Test
    void aFileThatPostEditAlreadyAnalyzedAtItsCurrentContentIsNotAnalyzedAgain() throws Exception {
        GateResult edit = agentEdit(APP, """
                package com.acme.simple;

                public class App {
                    public int one() {
                        System.out.println("debug");
                        return 1;
                    }
                }
                """, config());
        assertThat(edit.advisory()).extracting(Finding::ruleId).contains("JG-AI-001");

        GateResult result = endOfTurn(config());

        assertThat(result.advisory()).extracting(Finding::ruleId).doesNotContain("JG-AI-001");
    }

    @Test
    void uncommittedWorkAlreadyThereWhenTheSessionStartedIsNotBlamedOnTheAgent() throws Exception {
        Path wip = write("src/main/java/com/acme/simple/Wip.java", "package com.acme.simple;\nclass Wip { int x = \"broken\"; }\n");
        dirty.add(wip);
        agentEdit(APP, CLEAN, config()); // first edit of the session seeds the snapshot including Wip
        endOfTurn(config());
        runner.compileCalls.clear();

        GateResult result = endOfTurn(config());

        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        assertThat(runner.compileCalls).isEmpty();
    }

    @Test
    void withoutAnyEarlierRunOrLedgerTheWorkingTreeIsTakenToPredateTheSession() throws Exception {
        dirty.add(write("src/main/java/com/acme/simple/Wip.java", "package com.acme.simple;\nclass Wip {}\n"));

        GateResult result = endOfTurn(config());

        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        assertThat(runner.compileCalls).isEmpty();
    }

    @Test
    void aShellEditOfTheVeryFirstTurnIsCaughtWhenTheTurnStartWasRecorded() throws Exception {
        // Codex edits through shell commands, so its first hook of a session is the Stop: nothing but the turn start knows
        // which files were already there.
        Path wip = write("src/main/java/com/acme/simple/Wip.java", "package com.acme.simple;\nclass Wip { int x = \"broken\"; }\n");
        dirty.add(wip);
        new TurnBaseline(paths, () -> Optional.of(Set.copyOf(dirty)), new EndOfTurnState.Store(paths)).record("s1");
        dirty.add(write("src/main/java/com/acme/simple/Dao.java", """
                package com.acme.simple;

                import java.sql.Connection;
                import java.sql.PreparedStatement;
                import java.sql.SQLException;

                class Dao {
                    PreparedStatement run(Connection c, String id) throws SQLException {
                        return c.prepareStatement("SELECT * FROM t WHERE id = " + id);
                    }
                }
                """));

        GateResult result = endOfTurn(config());

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).extracting(Finding::ruleId).contains("JG-SEC-001");
        assertThat(result.blocking()).extracting(f -> f.file().map(Path::toString).orElse("")).noneMatch(f -> f.contains("Wip"));
    }

    // --- test integrity at the end of the turn ----------------------------------------------------

    /** The end-of-turn gate with a git whose HEAD holds {@code headContent} for every file. */
    private GateResult endOfTurnWithHead(JavaGuardConfig config, String headContent) {
        Function<ReactorModules, Optional<BuildRunner>> runners = reactor -> runnerSupplied;
        return new EndOfTurnGate(paths, config, tracker, () -> Optional.of(Set.copyOf(dirty)),
                file -> Optional.of(headContent.getBytes(StandardCharsets.UTF_8)), runners, (m, r) -> { }, deep, CLOCK)
                .run(event(GateKind.END_OF_TURN, List.of()));
    }

    @Test
    void aTestSourceDeletedByAShellCommandIsBlockedBecauseGitStillHasIt() throws Exception {
        agentEdit(APP, CLEAN, config());
        endOfTurn(config());
        Path testFile = project.resolve(APP_TEST);
        String original = Files.readString(testFile);
        Files.delete(testFile);
        dirty.add(testFile);

        GateResult result = endOfTurnWithHead(config(), original);

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).extracting(Finding::ruleId).contains("JG-TST-008");
    }

    @Test
    void aPomEditedByAShellCommandToSkipTestsIsBlocked() throws Exception {
        agentEdit(APP, CLEAN, config());
        endOfTurn(config());
        Path pom = project.resolve("pom.xml");
        String original = Files.readString(pom);
        Files.writeString(pom, original.replace("</properties>", "<skipTests>true</skipTests></properties>"));
        dirty.add(pom);

        GateResult result = endOfTurnWithHead(config(), original);

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).extracting(Finding::ruleId).contains("JG-TST-009");
        assertThat(runner.compileCalls).as("a build-file change is also built").isNotEmpty();
    }

    // --- migrations and configuration (plan Phase 23) ---------------------------------------------

    private static final String MIGRATION = "src/main/resources/db/migration/V1__init.sql";
    private static final String MIGRATION_SQL = "create table customer (id bigint primary key);\n";

    @Test
    void anAppliedMigrationEditedByAShellCommandIsBlockedWithoutBuildingAnything() throws Exception {
        write(MIGRATION, MIGRATION_SQL);
        agentEdit(APP, CLEAN, config());
        endOfTurn(config());
        int builds = runner.compileCalls.size();
        Files.writeString(project.resolve(MIGRATION), MIGRATION_SQL.replace("bigint", "int"));
        dirty.add(project.resolve(MIGRATION));

        GateResult result = endOfTurnWithHead(config(), MIGRATION_SQL);

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).extracting(Finding::ruleId).containsExactly("JG-DB-001");
        assertThat(runner.compileCalls).as("a migration is analyzed, not compiled").hasSize(builds);
    }

    @Test
    void anAppliedMigrationDeletedByAShellCommandIsBlocked() throws Exception {
        write(MIGRATION, MIGRATION_SQL);
        write("src/main/resources/db/migration/V2__more.sql", "alter table customer add column name varchar(10);\n");
        agentEdit(APP, CLEAN, config());
        endOfTurn(config());
        Files.delete(project.resolve(MIGRATION));
        dirty.add(project.resolve(MIGRATION));

        GateResult result = endOfTurnWithHead(config(), MIGRATION_SQL);

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).extracting(Finding::ruleId).containsExactly("JG-DB-002");
    }

    @Test
    void aSecretAddedToTheConfigurationByAShellCommandIsBlockedAndAnOldOneIsNot() throws Exception {
        String config = "spring.datasource.url=jdbc:h2:mem:x\nlegacy.api-key=Zx81kq0LmP4tY7uA\n";
        write("src/main/resources/application.properties", config);
        agentEdit(APP, CLEAN, config());
        endOfTurn(config());
        Files.writeString(project.resolve("src/main/resources/application.properties"),
                config + "spring.datasource.password=s3cr3t-Pa55word\n");
        dirty.add(project.resolve("src/main/resources/application.properties"));

        GateResult result = endOfTurnWithHead(config(), config);

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("JG-CFG-001");
            assertThat(f.line()).isEqualTo(3);
            assertThat(f.message()).contains("spring.datasource.password").doesNotContain("s3cr3t");
        });
    }

    @Test
    void deepAnalyzersAlsoRunWhenOnlyConfigurationChanged() throws Exception {
        List<List<Path>> seen = new ArrayList<>();
        deep.add(context -> {
            seen.add(context.changedFiles());
            return List.of();
        });
        agentEdit(APP, CLEAN, config());
        endOfTurn(config());
        int builds = runner.compileCalls.size();
        Path yml = write("src/main/resources/application.yml", "app:\n  name: demo\n");
        dirty.add(yml);

        endOfTurnWithHead(config(), "");

        assertThat(seen.get(seen.size() - 1)).contains(yml);
        assertThat(runner.compileCalls).hasSize(builds);
    }

    @Test
    void aDeletedTestThatNeverHeldTestsIsNotReported() throws Exception {
        agentEdit(APP, CLEAN, config());
        endOfTurn(config());
        Path helper = project.resolve("src/test/java/com/acme/simple/Support.java");
        Files.createDirectories(helper.getParent());
        String original = "package com.acme.simple;\nclass Support {}\n";
        dirty.add(helper); // gone from disk, present in HEAD, but it is not a test

        GateResult result = endOfTurnWithHead(config(), original);

        assertThat(result.blocking()).extracting(Finding::ruleId).doesNotContain("JG-TST-008");
    }

    // --- build outcomes ----------------------------------------------------------------------------

    @Test
    void aCompileErrorBlocksSkipsTheTestsAndStillRunsTheFileLocalRules() throws Exception {
        agentEdit(APP, CLEAN, config());
        runner.compileResult = failed(javac());
        Path shell = write("src/main/java/com/acme/simple/Noisy.java", """
                package com.acme.simple;

                class Noisy {
                    void m() {
                        System.out.println("debug");
                    }
                }
                """);
        dirty.add(shell);

        GateResult result = endOfTurn(config());

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).extracting(Finding::ruleId).containsExactly("JAVAC");
        assertThat(runner.testCalls).isEmpty();
        assertThat(result.advisory()).extracting(Finding::ruleId).contains("JG-AI-001");
        assertThat(refreshes).hasValue(0);
    }

    @Test
    void aRelatedTestFailureBlocks() throws Exception {
        agentEdit(APP, CLEAN, config());
        runner.testResult = failed(testFailure("related", Severity.CRITICAL));

        GateResult result = endOfTurn(config());

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).extracting(Finding::ruleId).containsExactly("TEST");
    }

    @Test
    void aPossiblyPreExistingTestFailureIsAdvisoryUnlessConfiguredToBlock() throws Exception {
        agentEdit(APP, CLEAN, config());
        runner.testResult = failed(testFailure("possibly-pre-existing", Severity.MAJOR));

        GateResult advisory = endOfTurn(config());
        assertThat(advisory.verdict()).isEqualTo(Verdict.ADVISE);
        assertThat(advisory.advisory()).extracting(Finding::ruleId).containsExactly("TEST");

        agentEdit(APP, CLEAN.replace("Hello", "Howdy"), config());
        GateResult blocking = endOfTurn(config("JAVAGUARD_TESTS_UNRELATEDFAILURES", "block"));
        assertThat(blocking.verdict()).isEqualTo(Verdict.BLOCK);
    }

    @Test
    void aHallucinatedDependencyBlocks() throws Exception {
        agentEdit(APP, CLEAN, config());
        runner.compileResult = failed(finding("JG-DEP-003", Category.DEPENDENCY, Severity.BLOCKER, "pom.xml",
                "Dependency x:y:jar:1 cannot be resolved", Map.of()));

        assertThat(endOfTurn(config()).verdict()).isEqualTo(Verdict.BLOCK);
    }

    @Test
    void anInfrastructureFailureOfTheBuildToolNeverBlocksAndReachesTheUserAsANote() throws Exception {
        agentEdit(APP, CLEAN, config());
        runner.compileResult = failed(finding("BUILD", Category.INFRASTRUCTURE, Severity.INFO, "pom.xml",
                "Maven ran out of memory", Map.of()));

        GateResult result = endOfTurn(config());

        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        assertThat(result.notes()).contains("Maven ran out of memory");
    }

    @Test
    void blockedFilesStayInTheChangedSetUntilARunPasses() throws Exception {
        agentEdit(APP, CLEAN, config());
        runner.testResult = failed(testFailure("related", Severity.CRITICAL));
        assertThat(endOfTurn(config()).verdict()).isEqualTo(Verdict.BLOCK);
        runner.compileCalls.clear();
        runner.testCalls.clear();

        // The agent changes nothing the gate can see, yet the failure must be re-verified, not forgotten.
        GateResult again = endOfTurn(config());
        assertThat(again.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(runner.compileCalls).hasSize(1);

        runner.testResult = ok();
        assertThat(endOfTurn(config()).verdict()).isEqualTo(Verdict.PASS);
        runner.compileCalls.clear();
        assertThat(endOfTurn(config()).verdict()).isEqualTo(Verdict.PASS);
        assertThat(runner.compileCalls).as("after a pass nothing is pending").isEmpty();
    }

    @Test
    void aMissingBuildToolIsANoteNotAFailure() throws Exception {
        agentEdit(APP, CLEAN, config());
        runnerSupplied = Optional.empty();

        GateResult result = endOfTurn(config());

        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        assertThat(result.notes()).anyMatch(n -> n.contains("No Maven executable"));
    }

    @Test
    void theClasspathCacheIsRefreshedOnlyAfterASuccessfulCompile() throws Exception {
        agentEdit(APP, CLEAN, config());
        endOfTurn(config());

        assertThat(refreshes).hasValue(1);
    }

    @Test
    void deepAnalyzersRunAfterAGoodCompileAndTheirFindingsAreIncluded() throws Exception {
        deep.add(context -> List.of(finding("JG-API-001", Category.API_COMPATIBILITY, Severity.MAJOR,
                APP, "Public API changed", Map.of())));
        agentEdit(APP, CLEAN, config());

        GateResult result = endOfTurn(config());

        assertThat(result.verdict()).isEqualTo(Verdict.ADVISE);
        assertThat(result.advisory()).extracting(Finding::ruleId).containsExactly("JG-API-001");
    }

    @Test
    void deepAnalyzersAreSkippedWhenTheCompileFailed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        deep.add(context -> {
            calls.incrementAndGet();
            return List.of();
        });
        agentEdit(APP, CLEAN, config());
        runner.compileResult = failed(javac());

        endOfTurn(config());

        assertThat(calls).hasValue(0);
    }

    // --- budget, guard, configuration ------------------------------------------------------------------

    @Test
    void anExhaustedBudgetSkipsTheBuildWithANoteDoesNotBlockAndKeepsTheFilesPending() throws Exception {
        agentEdit(APP, CLEAN, config());
        EndOfTurnGate starved = new EndOfTurnGate(paths, config(), tracker, () -> Optional.of(Set.copyOf(dirty)),
                GitClient.none(), reactor -> runnerSupplied, (m, r) -> { }, List.of(), CLOCK, Duration.ofMillis(1));

        GateResult result = starved.run(event(GateKind.END_OF_TURN, List.of()));

        assertThat(result.verdict()).isNotEqualTo(Verdict.BLOCK);
        assertThat(result.notes()).anyMatch(n -> n.contains("budget was exhausted"));
        assertThat(runner.compileCalls).isEmpty();

        // The next Stop, with budget, still verifies what was skipped.
        endOfTurn(config());
        assertThat(runner.compileCalls).hasSize(1);
    }

    @Test
    void theStopGuardAddsAStrategyHintAfterThreeBlocksWithoutReductionAndHaltsAtTheNextFiring() throws Exception {
        agentEdit(APP, CLEAN, config());
        runner.compileResult = failed(javac());

        GateResult first = endOfTurn(config());
        GateResult second = endOfTurn(config());
        GateResult third = endOfTurn(config());

        assertThat(first.escalation()).isEmpty();
        assertThat(second.escalation()).isEmpty();
        assertThat(third.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(third.escalation()).hasValueSatisfying(e -> {
            assertThat(e.level()).isEqualTo(1);
            assertThat(e.detector()).isEqualTo("stop-guard");
            assertThat(e.attempts()).isEqualTo(3);
            assertThat(e.guidance()).startsWith("You have attempted this 3 times with the same result.")
                    .contains("re-read " + APP).contains("Check the exact name and signature");
        });

        GateResult fourth = endOfTurn(config());
        GateResult fifth = endOfTurn(config());
        GateResult sixth = endOfTurn(config());

        assertThat(fourth.escalation()).hasValueSatisfying(e -> assertThat(e.level()).isEqualTo(1));
        assertThat(fifth.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(sixth.verdict()).as("consultation is off by default, so the second firing halts").isEqualTo(Verdict.HALT);
        assertThat(sixth.escalation()).hasValueSatisfying(e -> {
            assertThat(e.level()).isEqualTo(3);
            assertThat(e.guidance()).startsWith("STOP. Do not edit further.");
        });

        GateResult afterHalt = endOfTurn(config());
        assertThat(afterHalt.verdict()).as("the user took over; a new episode starts").isEqualTo(Verdict.BLOCK);
        assertThat(afterHalt.escalation()).isEmpty();
    }

    @Test
    void aReductionInTheBlockingCountIsProgressAndDoesNotEscalate() throws Exception {
        agentEdit(APP, CLEAN, config());
        runner.compileResult = failed(javac(), finding("JAVAC", Category.COMPILATION, Severity.CRITICAL, APP, "other", Map.of()));
        endOfTurn(config());
        endOfTurn(config());
        runner.compileResult = failed(javac()); // fewer blocking findings: progress

        GateResult third = endOfTurn(config());

        assertThat(third.escalation()).isEmpty();
    }

    @Test
    void aPassingTurnEndsTheEpisode() throws Exception {
        agentEdit(APP, CLEAN, config());
        runner.compileResult = failed(javac());
        endOfTurn(config());
        endOfTurn(config());
        runner.compileResult = ok();
        assertThat(endOfTurn(config()).verdict()).isEqualTo(Verdict.PASS);
        runner.compileResult = failed(javac());
        agentEdit(APP, CLEAN.replace("Hello", "Hi"), config());

        GateResult again = endOfTurn(config());

        assertThat(again.escalation()).isEmpty();
    }

    @Test
    void everyEndOfTurnRunIsRecordedInTheSessionHistory() throws Exception {
        agentEdit(APP, CLEAN, config());
        runner.compileResult = failed(javac());
        endOfTurn(config());
        runner.compileResult = ok();
        endOfTurn(config());
        endOfTurn(config()); // nothing changed: the fast path is recorded too

        List<String> lines = Files.readAllLines(paths.sessionDir("s1").resolve("history.jsonl"));

        assertThat(lines).hasSize(4); // the agent's post-edit run plus three end-of-turn runs
        assertThat(lines.get(1)).contains("\"gate\":\"end-of-turn\"").contains("\"verdict\":\"BLOCK\"");
        assertThat(lines.get(3)).contains("\"verdict\":\"PASS\"");
        assertThat(paths.sessionDir("s1").resolve("snapshots").resolve("2").resolve("index.json")).isRegularFile();
    }

    @Test
    void aDisabledFullGatePassesWithoutLookingAtAnything() throws Exception {
        agentEdit(APP, CLEAN, config());

        GateResult result = endOfTurn(config("JAVAGUARD_FULLGATE_ENABLED", "false"));

        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        assertThat(runner.compileCalls).isEmpty();
    }

    @Test
    void withoutABuildFileOnlyTheFileLocalChecksRun(@TempDir Path bare) throws Exception {
        JavaGuardPaths barePaths = new JavaGuardPaths(bare);
        barePaths.ensureLayout();
        ChangeTracker bareTracker = new ChangeTracker(barePaths, GitClient.none(), CLOCK);
        JavaGuardConfig config = ConfigLoader.load(ConfigSources.standard(bare, Optional.empty(), home, Map.of())).config();
        Set<Path> bareDirty = new HashSet<>();
        Path first = bare.resolve("First.java");
        Files.writeString(first, "class First {}\n");
        new PostEditGate(barePaths, config, bareTracker, CLOCK, () -> Optional.of(Set.copyOf(bareDirty)))
                .run(event(GateKind.POST_EDIT, List.of(FileChange.rewrite(first, ChangeType.ADD))));
        EndOfTurnGate gate = new EndOfTurnGate(barePaths, config, bareTracker, () -> Optional.of(Set.copyOf(bareDirty)),
                GitClient.none(), reactor -> runnerSupplied, (m, r) -> { }, List.of(), CLOCK);
        gate.run(event(GateKind.END_OF_TURN, List.of()));
        Path loose = bare.resolve("Loose.java");
        Files.writeString(loose, "class Loose { void m() { System.out.println(1); } }\n");
        bareDirty.add(loose);

        GateResult result = gate.run(event(GateKind.END_OF_TURN, List.of()));

        assertThat(result.notes()).anyMatch(n -> n.contains("No Maven or Gradle project"));
        assertThat(runner.compileCalls).isEmpty();
        assertThat(result.advisory()).extracting(Finding::ruleId).contains("JG-AI-001");
    }

    @Test
    void aGradleProjectIsBuiltAndTestedLikeAMavenOne() throws Exception {
        Files.delete(project.resolve("pom.xml"));
        write("settings.gradle.kts", "rootProject.name = \"simple\"\n");
        write("build.gradle.kts", "plugins { java }\n");
        agentEdit(APP, CLEAN, config());
        runner.compileResult = failed(javac());

        GateResult result = endOfTurn(config());

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(runner.compileCalls).isNotEmpty();
        assertThat(result.notes()).noneMatch(n -> n.contains("not supported"));
        assertThat(result.blocking()).extracting(Finding::ruleId).contains("JAVAC");
    }

    @Test
    void aFullReportIsWrittenForNonPassVerdicts() throws Exception {
        agentEdit(APP, CLEAN, config());
        runner.compileResult = failed(javac());

        GateResult result = endOfTurn(config());

        assertThat(result.fullReport()).isPresent();
        assertThat(project.resolve(result.fullReport().get())).isRegularFile();
    }
}
