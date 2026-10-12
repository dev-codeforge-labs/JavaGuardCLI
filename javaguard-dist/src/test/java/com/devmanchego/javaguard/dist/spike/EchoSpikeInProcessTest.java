package com.devmanchego.javaguard.dist.spike;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@link EchoSpike}'s logic in-process (System.exit disallows calling
 * {@code main} directly from a test). The real round-trip through the
 * packaged {@code echospike.exe} is {@code EchoSpikeExeIntegrationTest},
 * which needs the {@code packaging-spike} profile built first.
 */
class EchoSpikeInProcessTest {

    @Test
    void copiesStdinToStdoutUnchanged() throws Exception {
        byte[] payload = "{\"hook_event_name\":\"PostToolUse\"}".getBytes(StandardCharsets.UTF_8);

        byte[] out = runEchoLogicCapturingOutput(payload);

        assertThat(out).isEqualTo(payload);
    }

    @Test
    void handlesEmptyStdin() throws Exception {
        byte[] out = runEchoLogicCapturingOutput(new byte[0]);

        assertThat(out).isEmpty();
    }

    private static byte[] runEchoLogicCapturingOutput(byte[] input) throws Exception {
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream(input));
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            // Mirrors EchoSpike.main's body without the System.exit call.
            Callable<Void> body = () -> {
                try (InputStream in = System.in) {
                    in.transferTo(System.out);
                    System.out.flush();
                }
                return null;
            };
            body.call();
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
        return captured.toByteArray();
    }
}
