package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.cli.Main;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end hook behavior through picocli with the real adapters: stdin in,
 * stdout/stderr/exit code out (contracts sections 1, 3.3, 4.3, 5.3).
 */
class HookCommandTest {

    @TempDir
    Path projectRoot;

    private InputStream originalIn;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void captureStreams() {
        originalIn = System.in;
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        // The test JVM may run inside an AI agent that exports CLAUDE_PROJECT_DIR etc.
        // A slow shared runner must not exhaust the 3 s fast-gate budget (the gate would answer with a partial result and a note).
        ProcessEnvironment.overrideForTests(Map.of("JAVAGUARD_FASTGATE_BUDGETMILLIS", "120000"));
    }

    @AfterEach
    void restore() {
        System.setIn(originalIn);
        System.setOut(originalOut);
        System.setErr(originalErr);
        ProcessEnvironment.overrideForTests(null);
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            if (handler instanceof FileHandler) {
                handler.close();
                root.removeHandler(handler);
            }
        }
    }

    private int run(String payload, String... hookArgs) {
        System.setIn(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));
        String[] args = Stream.concat(Stream.of("--project-dir", projectRoot.toString(), "hook"), Stream.of(hookArgs))
                .toArray(String[]::new);
        return new CommandLine(new Main()).execute(args);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private String logText() throws IOException {
        Path logs = new JavaGuardPaths(projectRoot).logsDir();
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.list(logs)) {
            for (Path f : files.filter(p -> !p.toString().endsWith(".lck")).toList()) {
                sb.append(Files.readString(f, StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    @Test
    void claudePostEditPassesWithEmptyOutput() {
        int exit = run("{\"hook_event_name\":\"PostToolUse\",\"tool_name\":\"Edit\",\"tool_use_id\":\"t\","
                + "\"tool_input\":{\"file_path\":\"src/A.java\",\"old_string\":\"a\",\"new_string\":\"b\"}}",
                "--agent", "claude", "--gate", "post-edit");

        assertThat(exit).isZero();
        assertThat(stdout()).isEmpty();
        assertThat(stderr()).isEmpty();
    }

    @Test
    void geminiPassIsAnEmptyJsonObject() {
        int exit = run("{\"hook_event_name\":\"AfterTool\",\"tool_name\":\"replace\","
                + "\"tool_input\":{\"file_path\":\"src/A.java\",\"old_string\":\"a\",\"new_string\":\"b\"}}",
                "--agent", "gemini");

        assertThat(exit).isZero();
        assertThat(stdout()).isEqualTo("{}\n");
        assertThat(stderr()).isEmpty();
    }

    @Test
    void agentAndGateAreDetectedWhenOmitted() {
        int exit = run("{\"hook_event_name\":\"AfterAgent\",\"prompt_response\":\"done\"}");

        assertThat(exit).isZero();
        assertThat(stdout()).isEqualTo("{}\n"); // Gemini, end-of-turn, PASS
    }

    @Test
    void createsTheStateLayoutAndLogsTheEvent() throws IOException {
        run("{\"hook_event_name\":\"Stop\",\"session_id\":\"abc\"}", "--agent", "claude");

        JavaGuardPaths paths = new JavaGuardPaths(projectRoot);
        assertThat(paths.stateDir()).isDirectory();
        assertThat(paths.gitignoreFile()).isRegularFile();
        assertThat(logText()).contains("hook claude end-of-turn session=abc");
    }

    @Test
    void malformedPayloadWithKnownAgentReportsInternalErrorAndExitsZero() throws IOException {
        int exit = run("{not json", "--agent", "codex");

        assertThat(exit).isZero();
        assertThat(stdout()).contains("\"systemMessage\":\"JavaGuard internal error");
        assertThat(stderr()).isEmpty();
        assertThat(logText()).contains("Unusable hook payload");
    }

    @Test
    void malformedPayloadWithUnknownAgentIsSilent() {
        int exit = run("{not json");

        assertThat(exit).isZero();
        assertThat(stdout()).isEmpty();
        assertThat(stderr()).isEmpty();
    }

    @Test
    void unidentifiableAgentIsSilent() {
        int exit = run("{\"hook_event_name\":\"Notification\"}");

        assertThat(exit).isZero();
        assertThat(stdout()).isEmpty();
    }

    @Test
    void unknownEventWithKnownAgentAndNoGateFailsOpenWithMessage() {
        int exit = run("{\"hook_event_name\":\"SessionStart\"}", "--agent", "claude");

        assertThat(exit).isZero();
        assertThat(stdout()).contains("JavaGuard internal error");
    }

    @Test
    void invalidOptionValuesAreIgnoredNotFatal() {
        int exit = run("{\"hook_event_name\":\"AfterAgent\"}", "--agent", "copilot", "--gate", "whenever");

        assertThat(exit).isZero();
        assertThat(stdout()).isEqualTo("{}\n");
    }

    @Test
    void brokenProjectConfigFallsBackToDefaults() throws IOException {
        // The environment overrides the file: without this the invalid budget below would be replaced by the generous one.
        ProcessEnvironment.overrideForTests(Map.of());
        Files.writeString(projectRoot.resolve("javaguard.yml"), "fastGate: { budgetMillis: 0 }\n");

        int exit = run("{\"hook_event_name\":\"Stop\"}", "--agent", "claude");

        assertThat(exit).isZero();
        assertThat(stdout()).isEmpty();
        assertThat(logText()).contains("fastGate.budgetMillis").contains("Using built-in defaults");
    }

    @Test
    void disabledMarkerFileSkipsEverythingWithoutReadingStdin() throws Exception {
        JavaGuardPaths paths = new JavaGuardPaths(projectRoot);
        paths.ensureLayout();
        Files.createFile(paths.disabledMarkerFile());
        System.setIn(new InputStream() {
            @Override public int read() {
                throw new AssertionError("stdin must not be read when JavaGuard is disabled");
            }
        });

        int exit = new CommandLine(new Main())
                .execute("--project-dir", projectRoot.toString(), "hook", "--gate", "end-of-turn");

        assertThat(exit).isZero();
        assertThat(stdout()).isEmpty();
    }

    @Test
    void disabledEnvironmentVariableSkipsEverything() {
        ProcessEnvironment.overrideForTests(Map.of("JAVAGUARD_DISABLED", "1"));

        int exit = run("{\"hook_event_name\":\"AfterAgent\"}", "--agent", "gemini");

        assertThat(exit).isZero();
        assertThat(stdout()).isEmpty();
        assertThat(new JavaGuardPaths(projectRoot).stateDir()).doesNotExist();
    }
}
