package com.devmanchego.javaguard.cli.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 9: Stop payload -> hook -> end-of-turn gate -> real Maven on a small project -> the exact
 * agent output (contracts &sect;3.3), through the real adapters. Needs Maven on {@code PATH}, like every
 * build-tool test of this repository.
 */
class EndOfTurnHookEndToEndTest extends CommandTestBase {

    private static final String REL = "src/main/java/com/acme/App.java";
    private static final String TEST_REL = "src/test/java/com/acme/AppTest.java";

    private static final String POM = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.acme</groupId>
              <artifactId>e2e</artifactId>
              <version>1.0.0</version>
              <properties>
                <maven.compiler.release>21</maven.compiler.release>
                <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>5.12.2</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
            </project>
            """;

    private static final String APP = """
            package com.acme;

            public class App {
                public int twice(int n) {
                    return n * 2;
                }
            }
            """;

    private static final String APP_TEST = """
            package com.acme;

            import org.junit.jupiter.api.Test;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            class AppTest {
                @Test
                void twiceDoubles() {
                    assertEquals(8, new App().twice(4));
                }
            }
            """;

    @BeforeEach
    void project() throws IOException {
        // The base class empties the environment; the build tool needs the real PATH to be found.
        Map<String, String> env = new LinkedHashMap<>();
        System.getenv().forEach((k, v) -> {
            String upper = k.toUpperCase();
            if (!upper.startsWith("CLAUDE_") && !upper.startsWith("GEMINI_") && !upper.startsWith("CODEX_")
                    && !upper.startsWith("JAVAGUARD_")) {
                env.put(k, v);
            }
        });
        // A slow shared runner must not exhaust the 3 s fast-gate budget (the gate would answer with a partial result and a note).
        env.put("JAVAGUARD_FASTGATE_BUDGETMILLIS", "120000");
        ProcessEnvironment.overrideForTests(env);
        writeProjectFile("pom.xml", POM);
        writeProjectFile(REL, APP);
        writeProjectFile(TEST_REL, APP_TEST);
    }

    private String payload(String event, String extra) {
        return "{\"hook_event_name\":\"" + event + "\",\"session_id\":\"s1\",\"cwd\":\"" + jsonPath(projectRoot) + "\""
                + extra + "}";
    }

    private int agentWrites(String relative, String content) throws IOException {
        Path file = writeProjectFile(relative, content);
        return runWithStdin(payload("PostToolUse", ",\"tool_name\":\"Write\",\"tool_use_id\":\"t\",\"tool_input\":{\"file_path\":\""
                + jsonPath(file) + "\",\"content\":\"x\"},\"tool_response\":{\"type\":\"create\"}"),
                "hook", "--agent", "claude", "--gate", "post-edit");
    }

    private int stop() {
        return runWithStdin(payload("Stop", ",\"stop_hook_active\":false"), "hook", "--agent", "claude", "--gate", "end-of-turn");
    }

    @Test
    void aTurnWithoutJavaChangesPassesSilentlyAndFast() {
        long start = System.nanoTime();

        int exit = stop();

        assertThat(exit).isZero();
        assertThat(stdout()).isEmpty();
        assertThat(stderr()).isEmpty();
        assertThat((System.nanoTime() - start) / 1_000_000).as("milliseconds in-JVM").isLessThan(2000);
    }

    @Test
    void aCleanTurnCompilesRunsTheTestsAndPasses() throws Exception {
        assertThat(agentWrites(REL, APP)).isZero();
        assertThat(agentWrites(TEST_REL, APP_TEST)).isZero();
        clearOutput();

        int exit = stop();

        assertThat(exit).as(stderr()).isZero();
        assertThat(stdout()).isEmpty();
        assertThat(stderr()).isEmpty();
    }

    @Test
    void aCompileErrorBlocksTheStopWithTheReportOnStderr() throws Exception {
        assertThat(agentWrites(REL, APP.replace("n * 2", "\"two\" * n"))).isZero(); // POST_EDIT only advises on compile errors
        clearOutput();

        int exit = stop();

        assertThat(exit).isEqualTo(2);
        assertThat(stderr().stripTrailing()).startsWith("JAVAGUARD BLOCK - 1 blocking, 0 advisory - end-of-turn - 1 file(s)")
                .contains("## Blocking - fix these before continuing")
                .contains("[JAVAC Compilation error] src/main/java/com/acme/App.java:5")
                .contains("Problem: ")
                .endsWith("""
                        Guidance: fix root causes. Do not weaken, disable or delete tests. Add
                        "// javaguard:ignore <rule> -- <reason>" only for genuine false positives.""");
        assertThat(projectRoot.resolve(".javaguard/reports/last-report.md")).isRegularFile();
    }

    @Test
    void aFailingTestBlocksTheStopWithExpectedActualAndATrimmedTrace() throws Exception {
        assertThat(agentWrites(TEST_REL, APP_TEST)).isZero();
        assertThat(agentWrites(REL, APP.replace("n * 2", "n * 3"))).isZero();
        clearOutput();

        int exit = stop();

        assertThat(exit).isEqualTo(2);
        assertThat(stderr()).contains("[TEST Test failure] src/test/java/com/acme/AppTest.java:10")
                .contains("Problem: AppTest.twiceDoubles failed: expected: <8> but was: <12>")
                .contains("Trace: org.opentest4j.AssertionFailedError")
                .contains("at com.acme.AppTest.twiceDoubles(AppTest.java:10)")
                .contains("framework frame");
    }

    @Test
    void aShellEditedFileIsPickedUpFromTheWorkingTree() throws Exception {
        git("init", "-q");
        git("add", ".");
        git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "start");
        assertThat(agentWrites(TEST_REL, APP_TEST)).isZero(); // first edit of the session snapshots the (clean) tree
        assertThat(stop()).isZero();
        clearOutput();

        // A shell command rewrites production code behind the agent's back.
        Files.writeString(projectRoot.resolve(REL), APP.replace("n * 2", "n + 100"));

        int exit = stop();

        assertThat(exit).isEqualTo(2);
        assertThat(stderr()).contains("AppTest.twiceDoubles failed: expected: <8> but was: <104>");
    }

    @Test
    void afterTheFixTheNextStopPasses() throws Exception {
        assertThat(agentWrites(TEST_REL, APP_TEST)).isZero();
        assertThat(agentWrites(REL, APP.replace("n * 2", "n * 3"))).isZero();
        assertThat(stop()).isEqualTo(2);
        clearOutput();

        assertThat(agentWrites(REL, APP)).isZero();

        assertThat(stop()).isZero();
        assertThat(stdout()).isEmpty();
    }

    private void git(String... args) throws IOException, InterruptedException {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("git", "-C", projectRoot.toString()));
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).as("git " + String.join(" ", args)).isZero();
    }
}
