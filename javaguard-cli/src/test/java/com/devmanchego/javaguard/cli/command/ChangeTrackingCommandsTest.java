package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.change.BaselineSource;
import com.devmanchego.javaguard.change.ChangeTracker;
import com.devmanchego.javaguard.change.GitClient;
import com.devmanchego.javaguard.cli.ExitCode;
import com.devmanchego.javaguard.cli.Main;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Clock;
import java.util.Map;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 4 wiring: hooks feed the ledger; baseline/state commands clear it. */
class ChangeTrackingCommandsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORIGINAL = "class A {\n    int x = 1;\n}\n";

    @TempDir
    Path root;

    private InputStream originalIn;
    private PrintStream originalOut;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        originalIn = System.in;
        originalOut = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        ProcessEnvironment.overrideForTests(Map.of());
    }

    @AfterEach
    void tearDown() {
        System.setIn(originalIn);
        System.setOut(originalOut);
        ProcessEnvironment.overrideForTests(null);
        Logger rootLogger = Logger.getLogger("");
        for (Handler h : rootLogger.getHandlers()) {
            if (h instanceof FileHandler) {
                h.close();
                rootLogger.removeHandler(h);
            }
        }
    }

    private int hook(String event, Path file, String oldText, String newText) throws IOException {
        String payload = JSON.writeValueAsString(Map.of(
                "hook_event_name", event,
                "session_id", "sess-42",
                "cwd", root.toString(),
                "tool_name", "Edit",
                "tool_use_id", "t1",
                "tool_input", Map.of("file_path", file.toString(), "old_string", oldText, "new_string", newText)));
        System.setIn(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));
        return new CommandLine(new Main()).execute("--project-dir", root.toString(), "hook", "--agent", "claude");
    }

    private ChangeTracker tracker() {
        return new ChangeTracker(new JavaGuardPaths(root), GitClient.none(), Clock.systemUTC());
    }

    @Test
    void preEditThenPostEditThroughTheHookStoresTheSnapshotBaseline() throws IOException {
        Path a = root.resolve("src/A.java");
        Files.createDirectories(a.getParent());
        Files.writeString(a, ORIGINAL);

        assertThat(hook("PreToolUse", a, "int x = 1;", "int x = 2;")).isZero();
        Files.writeString(a, ORIGINAL.replace("int x = 1;", "int x = 2;"));
        assertThat(hook("PostToolUse", a, "int x = 1;", "int x = 2;")).isZero();

        ChangeTracker.Baseline baseline = tracker().baseline("sess-42", "src/A.java").orElseThrow();
        assertThat(baseline.source()).isEqualTo(BaselineSource.SNAPSHOT);
        assertThat(baseline.text()).isEqualTo(ORIGINAL);
        assertThat(tracker().ledger("sess-42").files().get("src/A.java").gateRuns()).hasSize(2);
    }

    @Test
    void postEditAloneReconstructsTheBaseline() throws IOException {
        Path a = root.resolve("src/A.java");
        Files.createDirectories(a.getParent());
        Files.writeString(a, ORIGINAL.replace("int x = 1;", "int x = 2;"));

        assertThat(hook("PostToolUse", a, "int x = 1;", "int x = 2;")).isZero();

        assertThat(tracker().baseline("sess-42", "src/A.java").orElseThrow().source())
                .isEqualTo(BaselineSource.RECONSTRUCTED);
    }

    @Test
    void baselineResetForgetsEverySession() throws IOException {
        Path a = root.resolve("src/A.java");
        Files.createDirectories(a.getParent());
        Files.writeString(a, ORIGINAL.replace("int x = 1;", "int x = 2;"));
        hook("PostToolUse", a, "int x = 1;", "int x = 2;");

        int exit = new CommandLine(new Main()).execute("--project-dir", root.toString(), "baseline", "reset");

        assertThat(exit).isZero();
        assertThat(tracker().ledger("sess-42").files()).isEmpty();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("1 session(s) reset");
    }

    @Test
    void stateClearRemovesTheSessionDirectory() throws IOException {
        Path a = root.resolve("src/A.java");
        Files.createDirectories(a.getParent());
        Files.writeString(a, ORIGINAL.replace("int x = 1;", "int x = 2;"));
        hook("PostToolUse", a, "int x = 1;", "int x = 2;");
        JavaGuardPaths paths = new JavaGuardPaths(root);
        assertThat(paths.sessionDir("sess-42")).isDirectory();

        int exit = new CommandLine(new Main()).execute("--project-dir", root.toString(), "state", "clear", "--all");

        assertThat(exit).isZero();
        assertThat(paths.sessionDir("sess-42")).doesNotExist();
    }

    @Test
    void stateClearNeedsExactlyOneTarget() {
        assertThat(new CommandLine(new Main()).execute("--project-dir", root.toString(), "state", "clear"))
                .isEqualTo(ExitCode.USAGE_ERROR.code());
        assertThat(new CommandLine(new Main()).execute("--project-dir", root.toString(), "state", "clear",
                "--all", "--session", "x")).isEqualTo(ExitCode.USAGE_ERROR.code());
    }
}
