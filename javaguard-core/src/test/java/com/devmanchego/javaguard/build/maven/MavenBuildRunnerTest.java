package com.devmanchego.javaguard.build.maven;

import com.devmanchego.javaguard.build.BuildResult;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.project.BuildToolExecutable;
import com.devmanchego.javaguard.project.BuildToolLocator;
import com.devmanchego.javaguard.project.ReactorModules;
import com.devmanchego.javaguard.testing.AffectedTestSelector;
import com.devmanchego.javaguard.testing.TestSelection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Plan Phase 8, against the real Maven on {@code PATH} (as every build-tool test in this project: the
 * repository itself needs Maven to build) and a copy of the multi-module fixture. Offline-first means
 * the first run of a machine exercises the online fallback for Surefire's test provider; that is
 * intended.
 */
class MavenBuildRunnerTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/maven-multimodule").toAbsolutePath().normalize();
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final Duration TIMEOUT = Duration.ofMinutes(4);

    @TempDir
    Path root;

    private Path moduleA;
    private Path moduleB;
    private MavenBuildRunner runner;

    @BeforeEach
    void project() throws IOException {
        try (Stream<Path> walk = Files.walk(FIXTURE)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path target = root.resolve(FIXTURE.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else if (!("/" + FIXTURE.relativize(source)).replace('\\', '/').contains("/target/")) {
                    Files.copy(source, target);
                }
            }
        }
        moduleA = root.resolve("module-a");
        moduleB = root.resolve("module-b");
        runner = runner(root);
    }

    private MavenBuildRunner runner(Path reactorRoot) {
        BuildToolExecutable maven = BuildToolLocator.maven(reactorRoot, false, System.getenv(), WINDOWS)
                .orElseThrow(() -> new AssertionError("Maven must be on PATH to run this test"));
        return new MavenBuildRunner(new ProcessRunner(), maven, ReactorModules.load(reactorRoot), reactorRoot,
                Optional.of(reactorRoot.resolve(".javaguard/logs")), true, List.of(), System.getenv());
    }

    /** The last part of what the Maven runs wrote under .javaguard/logs, for a skipped test to say why. */
    private String mavenLogs() throws IOException {
        Path logs = root.resolve(".javaguard/logs");
        if (!Files.isDirectory(logs)) {
            return "(no logs)";
        }
        StringBuilder out = new StringBuilder();
        try (var files = Files.list(logs)) {
            for (Path log : (Iterable<Path>) files.sorted()::iterator) {
                String text = Files.readString(log, StandardCharsets.UTF_8);
                out.append("--- ").append(log.getFileName()).append('\n')
                        .append(text.length() > 3000 ? text.substring(text.length() - 3000) : text).append('\n');
            }
        }
        return out.toString();
    }

    private void replaceIn(Path file, String from, String to) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(text).contains(from);
        Files.writeString(file, text.replace(from, to), StandardCharsets.UTF_8);
    }

    private TestSelection selectionFor(Path changed, JavaGuardConfig.TestScope scope) {
        return new AffectedTestSelector(ReactorModules.load(root),
                new JavaGuardConfig.Tests(scope, 50, false, JavaGuardConfig.Policy.ADVISE)).select(List.of(changed));
    }

    @Test
    void oneMavenRunCompilesTheChangeAndItsDependentsAndRunsTheSelectedTests() throws IOException {
        Path bar = moduleB.resolve("src/main/java/com/acme/moduleb/Bar.java");
        replaceIn(bar, "* 2", "* 3");
        TestSelection selection = selectionFor(bar, JavaGuardConfig.TestScope.AFFECTED);
        assertThat(runner.canCombine(selection)).isTrue();

        var both = runner.compileAndTest(List.of(moduleB), selection, TIMEOUT);

        assertThat(both.compile().success()).isTrue();
        assertThat(both.tests()).hasValueSatisfying(tests -> {
            assertThat(tests.success()).isFalse();
            assertThat(tests.findings()).singleElement().satisfies(f -> assertThat(f.message()).contains("expected: <84> but was: <126>"));
        });
    }

    @Test
    void aCompileErrorInADependentIsACompileFailureOfTheCombinedRunAndNoTestsAreReported() throws IOException {
        replaceIn(moduleA.resolve("src/main/java/com/acme/modulea/Foo.java"), "public int value()", "public int val()");
        Path foo = moduleA.resolve("src/main/java/com/acme/modulea/Foo.java");
        TestSelection selection = selectionFor(foo, JavaGuardConfig.TestScope.AFFECTED);

        var both = runner.compileAndTest(List.of(moduleA), selection, TIMEOUT);

        assertThat(both.compile().success()).isFalse();
        assertThat(both.compile().findings()).extracting(Finding::ruleId).containsExactly("JAVAC");
        assertThat(both.compile().findings().get(0).file()).contains(Path.of("module-b/src/main/java/com/acme/moduleb/Bar.java"));
        assertThat(both.tests()).isEmpty();
    }

    @Test
    void wholeModuleSelectionsAreNotCombined() {
        TestSelection whole = new TestSelection(JavaGuardConfig.TestScope.MODULE, List.of(), java.util.Set.of(moduleB), true,
                java.util.Set.of(), false, List.of());

        assertThat(runner.canCombine(whole)).isFalse();
        assertThat(runner.canCombine(TestSelection.none(JavaGuardConfig.TestScope.AFFECTED, List.of()))).isFalse();
    }

    @Test
    void aCleanProjectCompilesAndLeavesALog() {
        BuildResult result = runner.testCompile(List.of(moduleA), TIMEOUT);

        assertThat(result.success()).isTrue();
        assertThat(result.findings()).isEmpty();
        assertThat(result.log()).isPresent();
        assertThat(result.log().get()).isRegularFile();
    }

    @Test
    void anApiChangeInModuleABreaksDependentModuleBAndIsReportedBecauseOfAmd() throws IOException {
        replaceIn(moduleA.resolve("src/main/java/com/acme/modulea/Foo.java"), "public int value()", "public int val()");

        BuildResult result = runner.testCompile(List.of(moduleA), TIMEOUT);

        assertThat(result.success()).isFalse();
        assertThat(result.findings()).hasSize(1);
        Finding f = result.findings().get(0);
        assertThat(f.ruleId()).isEqualTo("JAVAC");
        assertThat(f.file()).contains(Path.of("module-b/src/main/java/com/acme/moduleb/Bar.java"));
        assertThat(f.message()).isEqualTo("cannot find symbol");
        assertThat(f.detail()).containsEntry("symbol", "method value()");
    }

    @Test
    void aHallucinatedDependencyBecomesABlockingDep003AfterTheOnlineRetry() throws IOException {
        replaceIn(moduleB.resolve("pom.xml"), "</dependencies>", """
                  <dependency>
                    <groupId>org.nonexistent.hallucinated</groupId>
                    <artifactId>magic-utils</artifactId>
                    <version>9.9.9</version>
                  </dependency>
                </dependencies>""");

        BuildResult result = runner.testCompile(List.of(moduleB), TIMEOUT);

        // A repository that does not answer "not found" for a missing artifact (a CI runner behind a proxy that returns 403, a
        // mirror, no network) says nothing about the artifact: JavaGuard then reports an infrastructure BUILD note and does not
        // block, which is the right behaviour but not what this test proves. Print what Maven said and skip.
        boolean repositoryDidNotAnswer = result.findings().stream().anyMatch(f -> f.ruleId().equals("BUILD"));
        if (repositoryDidNotAnswer) {
            System.out.println("[MavenBuildRunnerTest] the repository gave no usable 'not found' answer; Maven said:\n"
                    + mavenLogs());
        }
        assumeTrue(!repositoryDidNotAnswer, "the Maven repository did not answer 'not found' for a missing artifact (see the log)");

        assertThat(result.success()).isFalse();
        assertThat(result.findings()).as("findings: %s, notes: %s", result.findings(), result.notes())
                .extracting(Finding::ruleId).containsExactly("JG-DEP-003");
        Finding f = result.findings().get(0);
        assertThat(f.severity()).isEqualTo(Severity.BLOCKER);
        assertThat(f.message()).contains("org.nonexistent.hallucinated:magic-utils:jar:9.9.9");
        assertThat(result.notes()).anyMatch(n -> n.contains("retried online"));
    }

    @Test
    void aModuleMavenCannotSelectFallsBackToTheWholeReactor() throws IOException {
        Path notAModule = Files.createDirectories(root.resolve("not-a-module"));

        BuildResult result = runner.testCompile(List.of(notAModule), TIMEOUT);

        assertThat(result.success()).isTrue();
        assertThat(result.notes()).anyMatch(n -> n.contains("whole reactor"));
    }

    @Test
    void aFailingTestIsReportedWithExpectedActualAndATrimmedTrace() throws IOException {
        Path bar = moduleB.resolve("src/main/java/com/acme/moduleb/Bar.java");
        replaceIn(bar, "* 2", "* 3");

        BuildResult result = runner.runTests(selectionFor(bar, JavaGuardConfig.TestScope.AFFECTED), TIMEOUT);

        assertThat(result.executed()).isTrue();
        assertThat(result.success()).isFalse();
        assertThat(result.testsRun()).isEqualTo(1);
        assertThat(result.findings()).hasSize(1);
        Finding f = result.findings().get(0);
        assertThat(f.ruleId()).isEqualTo("TEST");
        assertThat(f.category()).isEqualTo(Category.TEST_FAILURE);
        assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(f.file()).contains(Path.of("module-b/src/test/java/com/acme/moduleb/BarTest.java"));
        assertThat(f.line()).isEqualTo(10);
        assertThat(f.detail()).containsEntry("test", "com.acme.moduleb.BarTest#doublesFooValue")
                .containsEntry("expected", "84").containsEntry("actual", "126")
                .containsEntry("classification", "related");
        String trace = f.detail().get("trace");
        assertThat(trace).startsWith("org.opentest4j.AssertionFailedError");
        assertThat(trace).contains("at com.acme.moduleb.BarTest.doublesFooValue(BarTest.java:10)")
                .contains("framework frame");
        assertThat(trace).doesNotContain("org.junit.platform.commons.util.ReflectionUtils");
    }

    @Test
    void passingTestsGiveASuccessfulRunWithTheCount() throws IOException {
        Path bar = moduleB.resolve("src/main/java/com/acme/moduleb/Bar.java");

        BuildResult result = runner.runTests(selectionFor(bar, JavaGuardConfig.TestScope.AFFECTED), TIMEOUT);

        assertThat(result.success()).isTrue();
        assertThat(result.testsRun()).isEqualTo(1);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void onlyTheSelectedClassesRunAndAnUnrelatedFailureIsPossiblyPreExisting() throws IOException {
        Path other = moduleB.resolve("src/test/java/com/acme/moduleb/OtherTest.java");
        Files.writeString(other, """
                package com.acme.moduleb;

                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.fail;

                class OtherTest {
                    @Test
                    void alwaysFails() {
                        fail("already broken");
                    }
                }
                """);
        Path bar = moduleB.resolve("src/main/java/com/acme/moduleb/Bar.java");

        BuildResult selected = runner.runTests(selectionFor(bar, JavaGuardConfig.TestScope.AFFECTED), TIMEOUT);
        assertThat(selected.success()).as("OtherTest is not selected, so it does not run").isTrue();
        assertThat(selected.testsRun()).isEqualTo(1);

        BuildResult wholeModule = runner.runTests(selectionFor(bar, JavaGuardConfig.TestScope.MODULE), TIMEOUT);
        assertThat(wholeModule.testsRun()).isEqualTo(2);
        assertThat(wholeModule.findings()).hasSize(1);
        Finding f = wholeModule.findings().get(0);
        assertThat(f.detail()).containsEntry("test", "com.acme.moduleb.OtherTest#alwaysFails")
                .containsEntry("classification", "possibly-pre-existing");
        assertThat(f.severity()).isEqualTo(Severity.MAJOR);
    }

    @Test
    void anEmptySelectionRunsNothing() {
        BuildResult result = runner.runTests(TestSelection.none(JavaGuardConfig.TestScope.AFFECTED, List.of()), TIMEOUT);

        assertThat(result.executed()).isFalse();
        assertThat(result.success()).isTrue();
    }

    @Test
    void aCompileErrorDuringATestRunIsReportedAsACompileErrorNotAsATestFailure() throws IOException {
        Path bar = moduleB.resolve("src/main/java/com/acme/moduleb/Bar.java");
        replaceIn(bar, "new Foo().value()", "new Foo().missing()");

        BuildResult result = runner.runTests(selectionFor(bar, JavaGuardConfig.TestScope.AFFECTED), TIMEOUT);

        assertThat(result.success()).isFalse();
        assertThat(result.findings()).extracting(Finding::ruleId).containsExactly("JAVAC");
    }

    @Test
    void aTimeoutKillsMavenAndIsAnInfrastructureFindingNotABlock() {
        BuildResult result = runner.testCompile(List.of(moduleA), Duration.ofMillis(200));

        assertThat(result.success()).isFalse();
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).category()).isEqualTo(Category.INFRASTRUCTURE);
        assertThat(result.findings().get(0).message()).contains("timed out");
    }

    @Test
    void aMavenThatCannotBeStartedIsAnInfrastructureFinding() {
        MavenBuildRunner broken = new MavenBuildRunner(new ProcessRunner(),
                new BuildToolExecutable(List.of(root.resolve("no-such-mvn").toString()), "path"),
                ReactorModules.load(root), root, Optional.empty(), true, List.of(), System.getenv());

        BuildResult result = broken.testCompile(List.of(moduleA), TIMEOUT);

        assertThat(result.success()).isFalse();
        assertThat(result.findings()).extracting(Finding::category).containsExactly(Category.INFRASTRUCTURE);
    }
}
