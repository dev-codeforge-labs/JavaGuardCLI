package com.devmanchego.javaguard.dist.spike;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Round-trips the packaged Launch4j executable itself: the actual plumbing
 * decision D12 depends on (.private/spec/notes/packaging-spike.md). Requires
 * {@code mvn -Ppackaging-spike -pl javaguard-dist package} to have produced
 * {@code target/echospike.exe} first; skipped otherwise so a plain
 * {@code mvn verify -Pintegration} does not fail on a machine that has not
 * run the spike build.
 */
@Tag("integration")
@EnabledOnOs(OS.WINDOWS)
class EchoSpikeExeIntegrationTest {

    private static final Path EXE = Path.of("target", "echospike.exe");

    @Test
    void stdinIsCopiedToStdoutByteForByteAndExitCodeIsPropagated() throws IOException, InterruptedException {
        assumeTrue(Files.isRegularFile(EXE), "Run 'mvn -Ppackaging-spike package' first: " + EXE + " not found");

        byte[] payload = "{\"hook_event_name\":\"PostToolUse\",\"tool_name\":\"Edit\"}"
                .getBytes(StandardCharsets.UTF_8);

        for (int expectedExit : new int[] { 0, 1, 2, 7 }) {
            ProcessBuilder pb = new ProcessBuilder(EXE.toAbsolutePath().toString(), String.valueOf(expectedExit));
            pb.redirectErrorStream(false);
            Process process = pb.start();
            process.getOutputStream().write(payload);
            process.getOutputStream().close();
            byte[] actualOutput = process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(finished).as("process should exit within 10s").isTrue();
            assertThat(process.exitValue()).as("exit code for input %d", expectedExit).isEqualTo(expectedExit);
            assertThat(actualOutput).as("stdout for exit code %d", expectedExit).isEqualTo(payload);
        }
    }

    @Test
    void emptyStdinProducesEmptyStdoutAndExitsZero() throws IOException, InterruptedException {
        assumeTrue(Files.isRegularFile(EXE), "Run 'mvn -Ppackaging-spike package' first: " + EXE + " not found");

        ProcessBuilder pb = new ProcessBuilder(EXE.toAbsolutePath().toString(), "0");
        Process process = pb.start();
        process.getOutputStream().close();
        byte[] actualOutput = process.getInputStream().readAllBytes();
        process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(process.exitValue()).isZero();
        assertThat(actualOutput).isEmpty();
    }
}
