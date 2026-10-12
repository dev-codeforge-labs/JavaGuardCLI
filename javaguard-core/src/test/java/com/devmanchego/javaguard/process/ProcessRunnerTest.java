package com.devmanchego.javaguard.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Specification section 8.1. */
class ProcessRunnerTest {

    private final ProcessRunner runner = new ProcessRunner();

    @Test
    void capturesTheExitCode(@TempDir Path dir) throws IOException {
        ProcessResult result = runner.run(TestJvm.command(ExitCodeTestHelper.class, "0"), dir, Map.of(), Duration.ofSeconds(30));
        assertThat(result.exitCode()).hasValue(0);
        assertThat(result.timedOut()).isFalse();

        ProcessResult nonZero = runner.run(TestJvm.command(ExitCodeTestHelper.class, "3"), dir, Map.of(), Duration.ofSeconds(30));
        assertThat(nonZero.exitCode()).hasValue(3);
        assertThat(nonZero.succeeded()).isFalse();
    }

    @Test
    void decodesOutputAsUtf8RegardlessOfConsoleCodePage(@TempDir Path dir) throws IOException {
        ProcessResult result = runner.run(TestJvm.command(OutputEchoTestHelper.class, "0"), dir, Map.of(), Duration.ofSeconds(30));

        assertThat(result.output()).isEqualTo(OutputEchoTestHelper.TEXT);
    }

    @Test
    void addsExtraEnvironmentVariablesForTheChild(@TempDir Path dir) throws IOException {
        ProcessResult result = runner.run(TestJvm.command(EnvEchoTestHelper.class, "JG_CUSTOM_VAR"), dir,
                Map.of("JG_CUSTOM_VAR", "hello"), Duration.ofSeconds(30));

        assertThat(result.output()).isEqualTo("hello");
    }

    @Test
    void alwaysSetsTheRecursionGuardEnvironmentVariable(@TempDir Path dir) throws IOException {
        ProcessResult result = runner.run(TestJvm.command(EnvEchoTestHelper.class, "JAVAGUARD_DISABLED"), dir,
                Map.of(), Duration.ofSeconds(30));

        assertThat(result.output()).isEqualTo("1");
    }

    @Test
    void extraEnvCannotOverrideTheRecursionGuard(@TempDir Path dir) throws IOException {
        ProcessResult result = runner.run(TestJvm.command(EnvEchoTestHelper.class, "JAVAGUARD_DISABLED"), dir,
                Map.of("JAVAGUARD_DISABLED", "0"), Duration.ofSeconds(30));

        assertThat(result.output()).isEqualTo("1");
    }

    @Test
    void runsInTheGivenWorkingDirectory(@TempDir Path dir) throws IOException {
        // "cd" alone with no args prints the current directory on cmd.exe; use java to stay portable instead.
        Path marker = dir.resolve("marker.txt");
        Files.writeString(marker, "present");

        ProcessResult result = runner.run(TestJvm.command(FileExistsTestHelper.class, "marker.txt"), dir, Map.of(),
                Duration.ofSeconds(30));

        assertThat(result.output()).isEqualTo("true");
    }

    @Test
    void boundsOutputAndKeepsTheTail(@TempDir Path dir) throws IOException {
        int lines = 500;
        ProcessResult result = runner.run(TestJvm.command(BigOutputTestHelper.class, String.valueOf(lines)), dir,
                Map.of(), Duration.ofSeconds(30), 200, Optional.empty());

        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.output()).startsWith("... output truncated, showing tail ...\n");
        assertThat(result.output()).doesNotContain("LINE-0\n");
        assertThat(result.output().stripTrailing()).endsWith("LINE-" + (lines - 1));
    }

    @Test
    void smallOutputIsNeverMarkedTruncated(@TempDir Path dir) throws IOException {
        ProcessResult result = runner.run(TestJvm.command(ExitCodeTestHelper.class, "0"), dir, Map.of(),
                Duration.ofSeconds(30), 4096, Optional.empty());

        assertThat(result.outputTruncated()).isFalse();
        assertThat(result.output()).isEmpty();
    }

    @Test
    void teeFileReceivesTheCompleteUntruncatedOutput(@TempDir Path dir) throws IOException {
        Path tee = dir.resolve("build.log");
        int lines = 500;

        ProcessResult result = runner.run(TestJvm.command(BigOutputTestHelper.class, String.valueOf(lines)), dir,
                Map.of(), Duration.ofSeconds(30), 200, Optional.of(tee));

        assertThat(result.outputTruncated()).isTrue();
        String teeContent = Files.readString(tee, StandardCharsets.UTF_8);
        assertThat(teeContent).contains("LINE-0\n").contains("LINE-" + (lines - 1));
    }

    @Test
    @Timeout(30)
    void timeoutKillsTheWholeProcessTree(@TempDir Path dir) throws IOException {
        Path pidDir = dir.resolve("pids");
        Files.createDirectories(pidDir);
        List<String> command = TestJvm.command(ProcessTreeTestHelper.class, "parent", pidDir.toString());

        ProcessResult result = runner.run(command, dir, Map.of(), Duration.ofMillis(500));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEmpty();

        long parentPid = awaitPid(pidDir.resolve("parent.pid"));
        long childPid = awaitPid(pidDir.resolve("child.pid"));

        boolean bothDead = waitUntil(Duration.ofSeconds(10), () -> !isAlive(parentPid) && !isAlive(childPid));
        assertThat(bothDead).as("parent (pid %d) and child (pid %d) should both be dead", parentPid, childPid).isTrue();
    }

    @Test
    void emptyCommandIsRejected(@TempDir Path dir) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> runner.run(List.of(), dir, Map.of(), Duration.ofSeconds(1)));
    }

    private static long awaitPid(Path pidFile) {
        boolean written = waitUntil(Duration.ofSeconds(10), () -> {
            try {
                return Files.isRegularFile(pidFile) && Files.size(pidFile) > 0;
            } catch (IOException e) {
                return false;
            }
        });
        assertThat(written).as("%s should have been written", pidFile).isTrue();
        try {
            return Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).strip());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** Polls {@code condition} until true or {@code timeout} elapses; returns the last observed value. */
    private static boolean waitUntil(Duration timeout, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            if (condition.getAsBoolean()) {
                return true;
            }
            if (Instant.now().isAfter(deadline)) {
                return false;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
