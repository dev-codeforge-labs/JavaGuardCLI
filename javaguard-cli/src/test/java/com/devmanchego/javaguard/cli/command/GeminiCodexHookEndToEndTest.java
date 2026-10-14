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
 * Plan Phase 16: payload -> hook -> gate -> exact agent output, in-JVM, for Gemini CLI (contracts section 4) and Codex
 * (section 5), through the real adapters and gates. The end-of-turn tests run a real Maven build, like the Claude ones.
 */
class GeminiCodexHookEndToEndTest extends CommandTestBase {

    private static final String REL = "src/main/java/com/acme/App.java";

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
            </project>
            """;

    private static final String CLEAN = """
            package com.acme;

            public class App {
                public int twice(int n) {
                    return n * 2;
                }
            }
            """;

    private static final String SQL_CONCAT = """
            package com.acme;

            import java.sql.Connection;
            import java.sql.PreparedStatement;
            import java.sql.SQLException;

            public class App {
                PreparedStatement find(Connection conn, String id) throws SQLException {
                    return conn.prepareStatement("SELECT * FROM t WHERE id = " + id);
                }
            }
            """;

    @BeforeEach
    void project() throws IOException {
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
    }

    // --- payload builders (shapes of contracts sections 4.2 and 5.2) -------------------------------

    private String common(String event, String session) {
        return "\"hook_event_name\":\"" + event + "\",\"session_id\":\"" + session + "\",\"cwd\":\"" + jsonPath(projectRoot) + "\"";
    }

    private int geminiWrite(String gate, String event, String content, String session) throws IOException {
        Path file = writeProjectFile(REL, content);
        return runWithStdin("{" + common(event, session) + ",\"tool_name\":\"write_file\",\"tool_input\":{\"file_path\":\""
                + jsonPath(file) + "\",\"content\":\"x\"}}", "hook", "--agent", "gemini", "--gate", gate);
    }

    private int geminiAfterAgent(String session) {
        return runWithStdin("{" + common("AfterAgent", session) + ",\"prompt\":\"p\",\"prompt_response\":\"done\",\"stop_hook_active\":false}",
                "hook", "--agent", "gemini", "--gate", "end-of-turn");
    }

    /** An {@code apply_patch} envelope that adds {@code relative} with {@code content}; the file is written on disk as Codex would. */
    private int codexPatch(String relative, String content, String session) throws IOException {
        writeProjectFile(relative, content);
        StringBuilder patch = new StringBuilder("*** Begin Patch\\n*** Add File: " + relative + "\\n");
        for (String line : content.split("\n", -1)) {
            if (!line.isEmpty() || !content.endsWith("\n")) {
                patch.append('+').append(line.replace("\"", "\\\"")).append("\\n");
            }
        }
        patch.append("*** End Patch");
        return runWithStdin("{" + common("PostToolUse", session) + ",\"model\":\"gpt-5\",\"turn_id\":\"t1\",\"tool_name\":\"apply_patch\","
                + "\"tool_input\":{\"command\":\"" + patch + "\"}}", "hook", "--agent", "codex", "--gate", "post-edit");
    }

    private int codexStop(String session) {
        return runWithStdin("{" + common("Stop", session) + ",\"model\":\"gpt-5\",\"turn_id\":\"t1\",\"stop_hook_active\":false,"
                + "\"last_assistant_message\":\"done\"}", "hook", "--agent", "codex", "--gate", "end-of-turn");
    }

    // --- Gemini ------------------------------------------------------------------------------------

    @Test
    void geminiPostEditOnBlockedCodeDeniesWithTheReportAsJsonOnStdout() throws Exception {
        int exit = geminiWrite("post-edit", "AfterTool", SQL_CONCAT, "g1");

        assertThat(exit).isZero(); // never exit 2 for Gemini: stdout must be one JSON document
        assertThat(stderr()).isEmpty();
        assertThat(stdout()).startsWith("{\"decision\":\"deny\",\"reason\":\"JAVAGUARD BLOCK - 1 blocking, 0 advisory - post-edit")
                .contains("JG-SEC-001");
        assertThat(stdout().strip().lines().count()).isEqualTo(1);
    }

    @Test
    void geminiPostEditOnCleanCodePassesWithAnEmptyObject() throws Exception {
        assertThat(geminiWrite("post-edit", "AfterTool", CLEAN, "g1")).isZero();
        assertThat(stdout()).isEqualTo("{}\n");
    }

    @Test
    void geminiReplaceIsAnalyzedAgainstTheBaselineTakenByBeforeTool() throws Exception {
        Path file = writeProjectFile(REL, CLEAN);
        String before = "{" + common("BeforeTool", "g2") + ",\"tool_name\":\"replace\",\"tool_input\":{\"file_path\":\"" + jsonPath(file)
                + "\",\"old_string\":\"return n * 2;\",\"new_string\":\"return n;\"}}";
        assertThat(runWithStdin(before, "hook", "--agent", "gemini", "--gate", "pre-edit")).isZero();
        assertThat(stdout()).isEqualTo("{}\n");
        clearOutput();

        // The tool then rewrote the file with a SQL concatenation introduced by the replacement.
        Files.writeString(file, SQL_CONCAT);
        String after = "{" + common("AfterTool", "g2") + ",\"tool_name\":\"replace\",\"tool_input\":{\"file_path\":\"" + jsonPath(file)
                + "\",\"old_string\":\"x\",\"new_string\":\"y\"},\"tool_response\":{\"llmContent\":\"ok\"}}";
        assertThat(runWithStdin(after, "hook", "--agent", "gemini", "--gate", "post-edit")).isZero();

        assertThat(stdout()).contains("\"decision\":\"deny\"").contains("JG-SEC-001");
    }

    @Test
    void geminiToolErrorMeansNothingWasEditedAndNothingIsChecked() throws Exception {
        Path file = writeProjectFile(REL, SQL_CONCAT);
        String failed = "{" + common("AfterTool", "g3") + ",\"tool_name\":\"write_file\",\"tool_input\":{\"file_path\":\"" + jsonPath(file)
                + "\",\"content\":\"x\"},\"tool_response\":{\"error\":\"permission denied\"}}";

        assertThat(runWithStdin(failed, "hook", "--agent", "gemini", "--gate", "post-edit")).isZero();
        assertThat(stdout()).isEqualTo("{}\n");
    }

    @Test
    void geminiEndOfTurnDeniesACompileErrorAndPassesAfterTheFix() throws Exception {
        assertThat(geminiWrite("post-edit", "AfterTool", CLEAN.replace("n * 2", "\"two\" * n"), "g4")).isZero();
        clearOutput();

        assertThat(geminiAfterAgent("g4")).isZero();
        assertThat(stdout()).startsWith("{\"decision\":\"deny\",\"reason\":\"JAVAGUARD BLOCK - 1 blocking, 0 advisory - end-of-turn")
                .contains("[JAVAC Compilation error] src/main/java/com/acme/App.java:5");
        clearOutput();

        assertThat(geminiWrite("post-edit", "AfterTool", CLEAN, "g4")).isZero();
        clearOutput();
        assertThat(geminiAfterAgent("g4")).isZero();
        assertThat(stdout()).isEqualTo("{}\n");
    }

    @Test
    void geminiKeptBlockedIsHaltedWithContinueFalseAndNoBell() throws Exception {
        String last = "";
        for (int attempt = 1; attempt <= 6; attempt++) {
            clearOutput();
            assertThat(geminiWrite("post-edit", "AfterTool", SQL_CONCAT.replace("public class App {", "public class App {\n    // attempt " + attempt), "g5"))
                    .isZero();
            last = stdout();
        }

        assertThat(last).startsWith("{\"continue\":false,\"stopReason\":\"JavaGuard halted the agent: the blocking problems persisted for 6 attempts")
                .doesNotContain("terminalSequence");
        assertThat(projectRoot.resolve(".javaguard/handoff/AI_HANDOFF_CONTEXT.md")).doesNotExist();
        assertThat(projectRoot.resolve("AI_HANDOFF_CONTEXT.md")).isRegularFile();
        assertThat(Files.readString(projectRoot.resolve("AI_HANDOFF_CONTEXT.md"))).contains("Agent: gemini");
    }

    // --- Codex -------------------------------------------------------------------------------------

    @Test
    void codexPostEditOnBlockedCodeBlocksWithJsonDecisionAndExitZero() throws Exception {
        int exit = codexPatch(REL, SQL_CONCAT, "c1");

        assertThat(exit).isZero();
        assertThat(stderr()).isEmpty();
        assertThat(stdout()).startsWith("{\"decision\":\"block\",\"reason\":\"JAVAGUARD BLOCK - 1 blocking, 0 advisory - post-edit")
                .contains("JG-SEC-001");
    }

    @Test
    void codexPostEditOnCleanCodePassesWithEmptyStdout() throws Exception {
        assertThat(codexPatch(REL, CLEAN, "c1")).isZero();
        assertThat(stdout()).isEmpty();
    }

    @Test
    void codexDeletedFilesAreRecordedButNotAnalyzed() throws Exception {
        writeProjectFile(REL, SQL_CONCAT);
        Files.delete(projectRoot.resolve(REL));
        String payload = "{" + common("PostToolUse", "c2") + ",\"model\":\"gpt-5\",\"turn_id\":\"t1\",\"tool_name\":\"apply_patch\","
                + "\"tool_input\":{\"command\":\"*** Begin Patch\\n*** Delete File: " + REL + "\\n*** End Patch\"}}";

        assertThat(runWithStdin(payload, "hook", "--agent", "codex", "--gate", "post-edit")).isZero();
        assertThat(stdout()).isEmpty();
    }

    @Test
    void codexNonPatchToolsAreIgnored() {
        String payload = "{" + common("PostToolUse", "c3") + ",\"model\":\"gpt-5\",\"turn_id\":\"t1\",\"tool_name\":\"shell\","
                + "\"tool_input\":{\"command\":\"ls\"}}";

        assertThat(runWithStdin(payload, "hook", "--agent", "codex", "--gate", "post-edit")).isZero();
        assertThat(stdout()).isEmpty();
    }

    @Test
    void codexStopBlocksACompileErrorWithAContinuationReasonAndPassesAfterTheFix() throws Exception {
        assertThat(codexPatch(REL, CLEAN.replace("n * 2", "\"two\" * n"), "c4")).isZero();
        clearOutput();

        assertThat(codexStop("c4")).isZero();
        assertThat(stdout()).startsWith("{\"decision\":\"block\",\"reason\":\"JAVAGUARD BLOCK - 1 blocking, 0 advisory - end-of-turn")
                .contains("[JAVAC Compilation error] src/main/java/com/acme/App.java:5");
        clearOutput();

        assertThat(codexPatch(REL, CLEAN, "c4")).isZero();
        clearOutput();
        assertThat(codexStop("c4")).isZero();
        assertThat(stdout()).isEmpty();
    }

    @Test
    void codexShellEditsAreCaughtAtTheStopFromTheWorkingTree() throws Exception {
        git("init", "-q");
        git("add", ".");
        git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "start");
        assertThat(codexPatch(REL, CLEAN, "c5")).isZero(); // seeds the session
        assertThat(codexStop("c5")).isZero();
        clearOutput();

        Files.writeString(projectRoot.resolve(REL), CLEAN.replace("n * 2", "\"two\" * n")); // `sed`, invisible to apply_patch

        assertThat(codexStop("c5")).isZero();
        assertThat(stdout()).contains("\"decision\":\"block\"").contains("JAVAC Compilation error");
    }

    private int codexPrompt(String session) {
        return runWithStdin("{" + common("UserPromptSubmit", session) + ",\"model\":\"gpt-5\",\"turn_id\":\"t1\",\"prompt\":\"p\"}",
                "hook", "--agent", "codex", "--gate", "turn-start");
    }

    private void committedProject() throws Exception {
        writeProjectFile(REL, CLEAN);
        git("init", "-q");
        git("add", ".");
        git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "start");
    }

    @Test
    void aShellEditOfTheFirstTurnIsCaughtBecauseTheTurnStartWasRecorded() throws Exception {
        committedProject();
        assertThat(codexPrompt("c6")).isZero();
        assertThat(stdout()).as("a UserPromptSubmit output would be added to the prompt").isEmpty();
        assertThat(stderr()).isEmpty();
        clearOutput();

        Files.writeString(projectRoot.resolve(REL), SQL_CONCAT); // `Set-Content` from PowerShell: no hook sees it

        assertThat(codexStop("c6")).isZero();
        assertThat(stdout()).contains("\"decision\":\"block\"").contains("JG-SEC-001");
    }

    @Test
    void withoutTheTurnStartTheFirstShellEditStaysUnseenAsDocumented() throws Exception {
        committedProject();
        Files.writeString(projectRoot.resolve(REL), SQL_CONCAT);

        assertThat(codexStop("c7")).isZero();
        assertThat(stdout()).isEmpty();
    }

    @Test
    void aByteOrderMarkWrittenByAShellIsExplainedInsteadOfLeftAsAGenericCompileError() throws Exception {
        committedProject();
        assertThat(codexPrompt("c8")).isZero();
        clearOutput();

        Files.writeString(projectRoot.resolve(REL), "﻿" + CLEAN.replace("App", "Fresh"));
        Files.move(projectRoot.resolve(REL), projectRoot.resolve("src/main/java/com/acme/Fresh.java"));

        assertThat(codexStop("c8")).isZero();
        assertThat(stdout()).contains("JAVAC").contains("byte order mark").contains("UTF-8 without BOM");
    }

    @Test
    void geminiBeforeAgentRecordsTheTurnStartAndAnswersWithAnEmptyJsonObject() throws Exception {
        committedProject();

        int exit = runWithStdin("{" + common("BeforeAgent", "g9") + ",\"prompt\":\"p\"}", "hook", "--agent", "gemini",
                "--gate", "turn-start");

        assertThat(exit).isZero();
        assertThat(stdout().strip()).isEqualTo("{}");
        assertThat(projectRoot.resolve(".javaguard/state/g9/endofturn.json")).isRegularFile();
    }

    private void git(String... args) throws IOException, InterruptedException {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("git", "-C", projectRoot.toString()));
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).as("git " + String.join(" ", args)).isZero();
    }
}
