package com.devmanchego.javaguard.build.gradle;

import com.devmanchego.javaguard.build.BuildResult;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.process.ProcessResult;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.project.BuildToolExecutable;
import com.devmanchego.javaguard.project.ReactorModules;
import com.devmanchego.javaguard.testing.TestSelection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan Phase 17 with {@link ProcessRunner} mocked (the boundary): the command lines Gradle receives, the offline-then-online
 * and whole-build fallbacks, and results read from JUnit XML. The same scenarios run against a real Gradle in
 * {@code GradleFixtureIntegrationTest}.
 */
class GradleBuildRunnerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @TempDir
    Path root;

    private ProcessRunner processes;
    private GradleBuildRunner runner;
    private Path api;
    private Path app;

    private ProcessResult ok() {
        return new ProcessResult(OptionalInt.of(0), "", false, false, Duration.ofMillis(5));
    }

    private ProcessResult failed(String output) {
        return new ProcessResult(OptionalInt.of(1), output, false, false, Duration.ofMillis(5));
    }

    @BeforeEach
    void project() throws IOException {
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"demo\"\ninclude(\"api\", \"app\")\n");
        api = Files.createDirectories(root.resolve("api"));
        app = Files.createDirectories(root.resolve("app"));
        Files.writeString(api.resolve("build.gradle.kts"), "plugins { java }\n");
        Files.writeString(app.resolve("build.gradle.kts"), "plugins { java }\ndependencies { implementation(project(\":api\")) }\n");
        processes = mock(ProcessRunner.class);
        runner = new GradleBuildRunner(processes, new BuildToolExecutable(List.of("/opt/gradle/bin/gradle"), "path"),
                ReactorModules.loadGradle(root), root, Optional.empty(), true, List.of("--no-daemon"), Map.of("GRADLE_OPTS", "-Xmx1g"));
    }

    private List<List<String>> commands(int expected) throws IOException {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(processes, times(expected)).run(captor.capture(), eq(root), any(), any(Duration.class), anyInt(), any());
        return new ArrayList<>(captor.getAllValues());
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    @Test
    void compileBuildsTheChangedModuleAndItsDependentsOffline() throws Exception {
        when(processes.run(any(), any(), any(), any(), anyInt(), any())).thenReturn(ok());

        BuildResult result = runner.testCompile(Set.of(api), TIMEOUT);

        assertThat(result.success()).isTrue();
        List<String> command = commands(1).get(0);
        assertThat(command.get(0)).isEqualTo("/opt/gradle/bin/gradle");
        assertThat(command).contains("-q", "--console=plain", "--continue", "--offline", "--no-daemon", "--init-script");
        assertThat(command).containsSubsequence(":api:testClasses").contains(":app:testClasses"); // app depends on api
    }

    @Test
    void aWholeBuildCompileUsesTheBareTask() throws Exception {
        when(processes.run(any(), any(), any(), any(), anyInt(), any())).thenReturn(ok());

        runner.testCompile(Set.of(), TIMEOUT);

        assertThat(commands(1).get(0)).endsWith("testClasses").doesNotContain(":api:testClasses");
    }

    @Test
    void theEnvironmentCarriesUtf8() throws Exception {
        when(processes.run(any(), any(), any(), any(), anyInt(), any())).thenReturn(ok());

        runner.testCompile(Set.of(api), TIMEOUT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> env = ArgumentCaptor.forClass(Map.class);
        verify(processes).run(any(), any(), env.capture(), any(Duration.class), anyInt(), any());
        assertThat(env.getValue().get("GRADLE_OPTS")).isEqualTo("-Xmx1g -Dfile.encoding=UTF-8");
    }

    @Test
    void anOfflineResolutionFailureIsRetriedOnline() throws Exception {
        when(processes.run(any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(failed("> No cached version of org.slf4j:slf4j-api:2.0.13 available for offline mode.\n"
                        + "> Could not resolve all files for configuration ':compileClasspath'.\n"))
                .thenReturn(ok());

        BuildResult result = runner.testCompile(Set.of(app), TIMEOUT);

        assertThat(result.success()).isTrue();
        assertThat(result.notes()).anyMatch(n -> n.contains("retried online"));
        List<List<String>> runs = commands(2);
        assertThat(runs.get(0)).contains("--offline");
        assertThat(runs.get(1)).doesNotContain("--offline");
    }

    @Test
    void aMissingArtifactOnlineIsReportedAsJgDep003() throws Exception {
        String missing = "* What went wrong:\n> Could not resolve all files for configuration ':compileClasspath'.\n"
                + "   > Could not find com.fake:nothing:9.9.9.\n";
        when(processes.run(any(), any(), any(), any(), anyInt(), any())).thenReturn(failed(missing));

        BuildResult result = runner.testCompile(Set.of(app), TIMEOUT);

        assertThat(result.success()).isFalse();
        assertThat(result.findings()).singleElement().satisfies(f -> assertThat(f.ruleId()).isEqualTo("JG-DEP-003"));
    }

    @Test
    void aProjectGradleCannotFindFallsBackToTheWholeBuild() throws Exception {
        when(processes.run(any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(failed("* What went wrong:\nProject 'app' not found in root project 'demo'.\n"))
                .thenReturn(ok());

        BuildResult result = runner.testCompile(Set.of(app), TIMEOUT);

        assertThat(result.success()).isTrue();
        assertThat(result.notes()).anyMatch(n -> n.contains("whole build"));
        assertThat(commands(2).get(1)).endsWith("testClasses");
    }

    @Test
    void compileErrorsBecomeJavacFindingsRelativeToTheProject() throws Exception {
        String file = app.resolve("src/main/java/App.java").toString();
        when(processes.run(any(), any(), any(), any(), anyInt(), any())).thenReturn(failed(
                file + ":3: error: cannot find symbol\n    foo();\n    ^\n  symbol:   method foo()\n1 error\n"));

        BuildResult result = runner.testCompile(Set.of(app), TIMEOUT);

        assertThat(result.success()).isFalse();
        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("JAVAC");
            assertThat(f.file()).contains(Path.of("app/src/main/java/App.java"));
        });
    }

    @Test
    void testsRunPerModuleWithTheirOwnFiltersAndReadTheJUnitXml() throws Exception {
        Path reports = Files.createDirectories(app.resolve("build/test-results/test"));
        when(processes.run(any(), any(), any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            Files.writeString(reports.resolve("TEST-com.acme.AppTest.xml"), """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <testsuite name="com.acme.AppTest" tests="2" skipped="0" failures="1" errors="0" timestamp="2026-10-05T10:00:00" hostname="h" time="0.02">
                      <testcase name="ok()" classname="com.acme.AppTest" time="0.001"/>
                      <testcase name="twiceDoubles()" classname="com.acme.AppTest" time="0.005">
                        <failure message="expected: &lt;8&gt; but was: &lt;12&gt;" type="org.opentest4j.AssertionFailedError">org.opentest4j.AssertionFailedError: expected: &lt;8&gt; but was: &lt;12&gt;
                    	at com.acme.AppTest.twiceDoubles(AppTest.java:10)
                    </failure>
                      </testcase>
                    </testsuite>
                    """);
            return failed("> Task :app:test FAILED\n\nFAILURE: Build failed with an exception.\n* What went wrong:\nExecution failed for task ':app:test'.\n");
        });
        TestSelection selection = new TestSelection(JavaGuardConfig.TestScope.AFFECTED, List.of("com.acme.AppTest", "com.acme.ApiTest"),
                Set.of(api, app), false, Set.of("com.acme.AppTest"), false, List.of());

        BuildResult result = runner.runTests(selection, TIMEOUT);

        List<String> command = commands(1).get(0);
        int apiTask = command.indexOf(":api:test");
        int appTask = command.indexOf(":app:test");
        assertThat(apiTask).isGreaterThanOrEqualTo(0);
        assertThat(appTask).isGreaterThanOrEqualTo(0);
        assertThat(command.subList(apiTask, apiTask + 5)).containsSubsequence("--tests", "com.acme.AppTest", "--tests", "com.acme.ApiTest");
        assertThat(command.subList(appTask, command.size())).contains("--tests", "com.acme.AppTest");
        assertThat(result.success()).isFalse();
        assertThat(result.testsRun()).isEqualTo(2);
        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.message()).contains("twiceDoubles").contains("expected: <8> but was: <12>");
        });
    }

    @Test
    void wholeModuleSelectionsPassNoFilter() throws Exception {
        when(processes.run(any(), any(), any(), any(), anyInt(), any())).thenReturn(ok());
        TestSelection whole = new TestSelection(JavaGuardConfig.TestScope.MODULE, List.of(), Set.of(app), true, Set.of(), false, List.of());

        BuildResult result = runner.runTests(whole, TIMEOUT);

        assertThat(commands(1).get(0)).contains(":app:test").doesNotContain("--tests");
        assertThat(result.success()).isTrue();
        assertThat(result.notes()).anyMatch(n -> n.contains("no new test reports"));
    }

    @Test
    void anEmptySelectionRunsNothing() throws Exception {
        BuildResult result = runner.runTests(TestSelection.none(JavaGuardConfig.TestScope.AFFECTED, List.of()), TIMEOUT);

        assertThat(result.executed()).isFalse();
        verify(processes, times(0)).run(any(), any(), any(), any(Duration.class), anyInt(), any());
    }

    @Test
    void aTimeoutIsInfrastructureNotATestFailure() throws Exception {
        when(processes.run(any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ProcessResult(OptionalInt.empty(), "", true, false, TIMEOUT));

        BuildResult result = runner.testCompile(Set.of(app), TIMEOUT);

        assertThat(result.success()).isFalse();
        assertThat(result.findings()).singleElement().satisfies(f -> assertThat(f.message()).contains("timed out"));
    }

    @Test
    void aGradleThatCannotBeStartedIsReportedNotThrown() throws Exception {
        when(processes.run(any(), any(), any(), any(), anyInt(), any())).thenThrow(new IOException("no such file"));

        BuildResult result = runner.testCompile(Set.of(app), TIMEOUT);

        assertThat(result.success()).isFalse();
        assertThat(result.findings()).hasSize(1);
    }
}
