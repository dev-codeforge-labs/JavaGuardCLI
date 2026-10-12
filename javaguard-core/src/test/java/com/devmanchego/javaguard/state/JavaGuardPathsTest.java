package com.devmanchego.javaguard.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaGuardPathsTest {

    @Test
    void ensureLayoutCreatesEveryDirectory(@TempDir Path projectRoot) {
        JavaGuardPaths paths = new JavaGuardPaths(projectRoot);

        paths.ensureLayout();

        assertThat(paths.stateDir()).isDirectory();
        assertThat(paths.cacheDir()).isDirectory();
        assertThat(paths.classpathCacheDir()).isDirectory();
        assertThat(paths.baselineFindingsCacheDir()).isDirectory();
        assertThat(paths.tmpDir()).isDirectory();
        assertThat(paths.sessionsDir()).isDirectory();
        assertThat(paths.reportsDir()).isDirectory();
        assertThat(paths.handoffDir()).isDirectory();
        assertThat(paths.logsDir()).isDirectory();
    }

    @Test
    void ensureLayoutIsIdempotent(@TempDir Path projectRoot) {
        JavaGuardPaths paths = new JavaGuardPaths(projectRoot);

        paths.ensureLayout();
        paths.ensureLayout();

        assertThat(paths.stateDir()).isDirectory();
    }

    @Test
    void ensureLayoutWritesSelfIgnore(@TempDir Path projectRoot) throws IOException {
        JavaGuardPaths paths = new JavaGuardPaths(projectRoot);

        paths.ensureLayout();

        assertThat(paths.gitignoreFile()).isRegularFile();
        List<String> lines = Files.readAllLines(paths.gitignoreFile(), StandardCharsets.UTF_8);
        assertThat(lines).contains("*");
    }

    @Test
    void ensureLayoutNeverTouchesTheProjectsOwnGitignore(@TempDir Path projectRoot) throws IOException {
        Path projectGitignore = projectRoot.resolve(".gitignore");
        Files.writeString(projectGitignore, "target/\n*.class\n", StandardCharsets.UTF_8);

        new JavaGuardPaths(projectRoot).ensureLayout();

        assertThat(Files.readString(projectGitignore, StandardCharsets.UTF_8))
                .isEqualTo("target/\n*.class\n");
    }

    @Test
    void ensureLayoutDoesNotDuplicateExistingSelfIgnore(@TempDir Path projectRoot) throws IOException {
        JavaGuardPaths paths = new JavaGuardPaths(projectRoot);
        Files.createDirectories(paths.stateDir());
        Files.writeString(paths.gitignoreFile(), "*\n", StandardCharsets.UTF_8);
        long originalSize = Files.size(paths.gitignoreFile());

        paths.ensureLayout();

        assertThat(Files.size(paths.gitignoreFile())).isEqualTo(originalSize);
    }

    @Test
    void isDisabledIsFalseByDefault(@TempDir Path projectRoot) {
        assertThat(new JavaGuardPaths(projectRoot).isDisabled()).isFalse();
    }

    @Test
    void isDisabledHonorsTheEnvironmentVariable(@TempDir Path projectRoot) {
        JavaGuardPaths paths = new JavaGuardPaths(projectRoot);

        assertThat(paths.isDisabled(java.util.Map.of("JAVAGUARD_DISABLED", "1"))).isTrue();
        assertThat(paths.isDisabled(java.util.Map.of("JAVAGUARD_DISABLED", "0"))).isFalse();
        assertThat(paths.isDisabled(java.util.Map.of())).isFalse();
    }

    @Test
    void isDisabledIsTrueWhenMarkerFileExists(@TempDir Path projectRoot) throws IOException {
        JavaGuardPaths paths = new JavaGuardPaths(projectRoot);
        paths.ensureLayout();
        Files.createFile(paths.disabledMarkerFile());

        assertThat(paths.isDisabled()).isTrue();
    }

    @Test
    void sessionDirSanitizesUntrustedSessionIds(@TempDir Path projectRoot) {
        JavaGuardPaths paths = new JavaGuardPaths(projectRoot);

        Path sessionDir = paths.sessionDir("../../etc/passwd");

        assertThat(sessionDir.startsWith(paths.sessionsDir())).isTrue();
        assertThat(sessionDir.normalize()).isEqualTo(sessionDir);
    }

    @Test
    void sessionDirNeverProducesATraversalSegment(@TempDir Path projectRoot) {
        JavaGuardPaths paths = new JavaGuardPaths(projectRoot);

        Path sessionDir = paths.sessionDir("..");

        assertThat(sessionDir.getParent()).isEqualTo(paths.sessionsDir());
    }

    @Test
    void sessionDirFallsBackToManualForBlankId(@TempDir Path projectRoot) {
        JavaGuardPaths paths = new JavaGuardPaths(projectRoot);

        assertThat(paths.sessionDir(null)).isEqualTo(paths.sessionsDir().resolve("manual"));
        assertThat(paths.sessionDir("  ")).isEqualTo(paths.sessionsDir().resolve("manual"));
    }
}
