package com.devmanchego.javaguard.build.maven;

import com.devmanchego.javaguard.build.BuildResult;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.process.ProcessResult;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.project.BuildToolExecutable;
import com.devmanchego.javaguard.project.ReactorModules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan Phase 8 (and the Maven 4 findings of Phase 9's follow-up): the runner's fallbacks, driven by a scripted
 * {@link ProcessRunner} that replays real Maven output. No Maven is started.
 */
class MavenBuildRunnerFallbackTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/maven-multimodule").toAbsolutePath().normalize();

    @TempDir
    Path root;

    private ProcessRunner process;
    private MavenBuildRunner runner;

    @BeforeEach
    void setUp() throws IOException {
        try (Stream<Path> walk = Files.walk(FIXTURE)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path target = root.resolve(FIXTURE.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else if (!source.toString().replace('\\', '/').contains("/target/")) {
                    Files.copy(source, target);
                }
            }
        }
        process = mock(ProcessRunner.class);
        runner = new MavenBuildRunner(process, new BuildToolExecutable(List.of("mvn"), "path"), ReactorModules.load(root), root,
                Optional.empty(), true, List.of(), Map.of());
    }

    private static String sample(String name) throws IOException {
        try (var in = MavenBuildRunnerFallbackTest.class.getResourceAsStream("/maven-output/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ProcessResult failed(String output) {
        return new ProcessResult(OptionalInt.of(1), output, false, false, Duration.ZERO);
    }

    private static ProcessResult succeeded() {
        return new ProcessResult(OptionalInt.of(0), "", false, false, Duration.ZERO);
    }

    private List<List<String>> commands() throws IOException {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(process, org.mockito.Mockito.atLeastOnce()).run(captor.capture(), any(), any(), any(), anyInt(), any());
        return captor.getAllValues();
    }

    @Test
    void maven4OfflineMissCannotAccuseAnArtifactThatOnlyNeedsDownloading() throws Exception {
        // Maven 4 words "not cached, offline" exactly like "does not exist"; the online retry finds the artifact.
        when(process.run(any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(failed(sample("maven-4.0.0-rc-7-dependency-offline.txt")))
                .thenReturn(succeeded());

        BuildResult result = runner.testCompile(List.of(root.resolve("module-b")), Duration.ofMinutes(1));

        assertThat(result.success()).isTrue();
        assertThat(result.findings()).isEmpty();
        assertThat(result.notes()).anyMatch(n -> n.contains("retried online"));
        List<List<String>> commands = commands();
        assertThat(commands).hasSize(2);
        assertThat(commands.get(0)).contains("-o");
        assertThat(commands.get(1)).doesNotContain("-o");
    }

    @Test
    void maven4HallucinatedDependencyIsReportedOnlyAfterTheOnlineRetryAlsoFails() throws Exception {
        when(process.run(any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(failed(sample("maven-4.0.0-rc-7-dependency-offline.txt")))
                .thenReturn(failed(sample("maven-4.0.0-rc-7-dependency-missing.txt")));

        BuildResult result = runner.testCompile(List.of(root.resolve("module-b")), Duration.ofMinutes(1));

        assertThat(result.success()).isFalse();
        assertThat(result.findings()).extracting(Finding::ruleId).containsExactly("JG-DEP-003");
        verify(process, times(2)).run(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void maven3OfflineMissIsAlsoConfirmedOnline() throws Exception {
        when(process.run(any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(failed(sample("maven-3.8.9-dependency-offline.txt")))
                .thenReturn(failed(sample("maven-3.8.9-dependency-missing.txt")));

        BuildResult result = runner.testCompile(List.of(root.resolve("module-b")), Duration.ofMinutes(1));

        assertThat(result.findings()).extracting(Finding::ruleId).containsExactly("JG-DEP-003");
        assertThat(result.findings().get(0).detail()).containsEntry("artifact", "org.nonexistent.hallucinated:magic-utils:jar:9.9.9");
    }

    @Test
    void anUnselectableProjectFallsBackToTheWholeReactorInEveryMavenVersion() throws Exception {
        for (String version : List.of("maven-3.8.9", "maven-3.9.4", "maven-4.0.0-rc-7")) {
            ProcessRunner scripted = mock(ProcessRunner.class);
            when(scripted.run(any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(failed(sample(version + "-selection-failure.txt")))
                    .thenReturn(succeeded());
            MavenBuildRunner local = new MavenBuildRunner(scripted, new BuildToolExecutable(List.of("mvn"), "path"),
                    ReactorModules.load(root), root, Optional.empty(), true, List.of(), Map.of());

            BuildResult result = local.testCompile(List.of(root.resolve("module-b")), Duration.ofMinutes(1));

            assertThat(result.success()).as(version).isTrue();
            assertThat(result.notes()).as(version).anyMatch(n -> n.contains("whole reactor"));
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(scripted, times(2)).run(captor.capture(), any(), any(), any(), anyInt(), any());
            assertThat(captor.getAllValues().get(0)).as(version).contains("-pl");
            assertThat(captor.getAllValues().get(1)).as(version).doesNotContain("-pl");
        }
    }

    @Test
    void aCompileErrorIsNotRetriedBecauseNothingAboutItDependsOnTheNetwork() throws Exception {
        when(process.run(any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(failed(sample("maven-4.0.0-rc-7-compile-error-quiet.txt")));

        BuildResult result = runner.testCompile(List.of(root.resolve("module-a")), Duration.ofMinutes(1));

        assertThat(result.findings()).extracting(Finding::ruleId).containsExactly("JAVAC");
        verify(process, times(1)).run(any(), any(), any(), any(), anyInt(), any());
    }
}
