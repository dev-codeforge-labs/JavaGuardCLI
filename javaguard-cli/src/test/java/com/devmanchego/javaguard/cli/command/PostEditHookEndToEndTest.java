package com.devmanchego.javaguard.cli.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 7: payload -> hook -> gate on a project -> the exact agent output (contracts &sect;3.3),
 * through the real adapters and the real analysis engines.
 */
class PostEditHookEndToEndTest extends CommandTestBase {

    private static final String REL = "src/main/java/com/acme/App.java";

    private static final String SQL_CONCAT = """
            package com.acme;

            import java.sql.Connection;
            import java.sql.PreparedStatement;
            import java.sql.SQLException;

            public class App {
                PreparedStatement run(Connection conn, String id) throws SQLException {
                    return conn.prepareStatement("SELECT * FROM t WHERE id = " + id);
                }
            }
            """;

    private static final String CLEAN = """
            package com.acme;

            public class App {
                public int one() {
                    return 1;
                }
            }
            """;

    @BeforeEach
    void project() throws IOException {
        createMavenProject();
    }

    private String payload(String tool, String input, String response) {
        return "{\"hook_event_name\":\"PostToolUse\",\"session_id\":\"s1\",\"cwd\":\"" + jsonPath(projectRoot)
                + "\",\"tool_name\":\"" + tool + "\",\"tool_use_id\":\"t1\",\"tool_input\":" + input
                + ",\"tool_response\":" + response + "}";
    }

    private String writePayload() {
        return payload("Write", "{\"file_path\":\"" + jsonPath(projectRoot.resolve(REL)) + "\",\"content\":\"x\"}",
                "{\"type\":\"create\"}");
    }

    private int postEdit() {
        return runWithStdin(writePayload(), "hook", "--agent", "claude", "--gate", "post-edit");
    }

    @Test
    void aCleanFilePassesSilently() throws Exception {
        writeProjectFile(REL, CLEAN);

        int exit = postEdit();

        assertThat(exit).isZero();
        assertThat(stdout()).isEmpty();
        assertThat(stderr()).isEmpty();
    }

    @Test
    void aSeededSqlConcatenationIsBlockedWithTheReportOnStderr() throws Exception {
        writeProjectFile(REL, SQL_CONCAT);

        int exit = postEdit();

        assertThat(exit).isEqualTo(2);
        assertThat(stderr()).startsWith("JAVAGUARD BLOCK - 1 blocking")
                .contains("## Blocking - fix these before continuing")
                .contains("[JG-SEC-001")
                .contains("src/main/java/com/acme/App.java:9")
                .contains("Fix:");
    }

    @Test
    void theUserIsToldWhyCompilationWasSkippedAlongsideTheBlock() throws Exception {
        writeProjectFile(REL, SQL_CONCAT);

        postEdit();

        // BLOCK carries user notices as JSON on stdout next to the exit-2 report (contracts section 3.3).
        assertThat(stdout()).contains("\"systemMessage\"").contains("classpath cache is missing");
    }

    @Test
    void anAdvisoryFindingReachesTheModelAsAdditionalContext() throws Exception {
        writeProjectFile(REL, """
                package com.acme;

                public class App {
                    public int one() {
                        System.out.println("debug");
                        return 1;
                    }
                }
                """);

        int exit = postEdit();

        assertThat(exit).isZero();
        assertThat(stdout()).contains("\"hookEventName\":\"PostToolUse\"")
                .contains("\"additionalContext\":\"JAVAGUARD ADVISE")
                .contains("JG-AI-001");
        assertThat(stderr()).isEmpty();
    }

    @Test
    void aSecondEditIsJudgedAgainstTheBaselineOfTheFirst() throws Exception {
        writeProjectFile(REL, CLEAN);
        assertThat(runWithStdin(payload("Write",
                "{\"file_path\":\"" + jsonPath(projectRoot.resolve(REL)) + "\",\"content\":\"x\"}", "{\"type\":\"create\"}"),
                "hook", "--agent", "claude", "--gate", "post-edit")).isZero();
        clearOutput();

        // The agent now edits one line in; only that change may be judged.
        Path file = projectRoot.resolve(REL);
        Files.writeString(file, CLEAN.replace("return 1;", "System.out.println(\"debug\");\n        return 1;"));
        String edit = payload("Edit", "{\"file_path\":\"" + jsonPath(file)
                + "\",\"old_string\":\"return 1;\",\"new_string\":\"System.out.println(\\\"debug\\\");\\n        return 1;\"}",
                "{}");

        int exit = runWithStdin(edit, "hook", "--agent", "claude", "--gate", "post-edit");

        assertThat(exit).isZero();
        assertThat(stdout()).contains("JG-AI-001");
    }

    @Test
    void aFileThatDoesNotParseBlocksAsASyntaxError() throws Exception {
        writeProjectFile(REL, "package com.acme;\n\npublic class App {\n    void m( {\n}\n");

        int exit = postEdit();

        assertThat(exit).isEqualTo(2);
        assertThat(stderr()).contains("[JG-SYN-001");
    }

    @Test
    void disablingATestIsBlockedWithTheIntegrityGuidance() throws Exception {
        String test = REL.replace("main", "test").replace("App.java", "AppTest.java");
        String original = "package com.acme;\n\nimport org.junit.jupiter.api.Test;\n\nclass AppTest {\n    @Test\n"
                + "    void works() {\n        org.junit.jupiter.api.Assertions.assertEquals(1, 1);\n    }\n}\n";
        Path file = writeProjectFile(test, original.replace("    @Test\n", "    @Test\n    @Disabled\n"));
        String edit = "{\"hook_event_name\":\"PostToolUse\",\"session_id\":\"s1\",\"cwd\":\"" + jsonPath(projectRoot)
                + "\",\"tool_name\":\"Edit\",\"tool_use_id\":\"t1\",\"tool_input\":{\"file_path\":\"" + jsonPath(file)
                + "\",\"old_string\":\"    @Test\\n\",\"new_string\":\"    @Test\\n    @Disabled\\n\"},\"tool_response\":{}}";

        int exit = runWithStdin(edit, "hook", "--agent", "claude", "--gate", "post-edit");

        assertThat(exit).isEqualTo(2);
        assertThat(stderr()).contains("[JG-TST-002 Test disabled] src/test/java/com/acme/AppTest.java")
                .contains("Test integrity: Tests define expected behavior. Do not weaken, disable or delete them");
    }

    @Test
    void aSuppressionTheAgentAddedToSilenceAFindingStillReachesTheUser() throws Exception {
        String test = REL.replace("main", "test").replace("App.java", "AppTest.java");
        String original = "package com.acme;\n\nimport org.junit.jupiter.api.Test;\n\nclass AppTest {\n    @Test\n"
                + "    void works() {\n        org.junit.jupiter.api.Assertions.assertEquals(1, 1);\n    }\n}\n";
        String suppressed = "    // javaguard:ignore JG-TST-002 -- flaky\n    @Test\n    @Disabled\n";
        Path file = writeProjectFile(test, original.replace("    @Test\n", suppressed));
        String edit = "{\"hook_event_name\":\"PostToolUse\",\"session_id\":\"s1\",\"cwd\":\"" + jsonPath(projectRoot)
                + "\",\"tool_name\":\"Edit\",\"tool_use_id\":\"t1\",\"tool_input\":{\"file_path\":\"" + jsonPath(file)
                + "\",\"old_string\":\"    @Test\\n\",\"new_string\":\"    // javaguard:ignore JG-TST-002 -- flaky\\n"
                + "    @Test\\n    @Disabled\\n\"},\"tool_response\":{}}";

        int exit = runWithStdin(edit, "hook", "--agent", "claude", "--gate", "post-edit");

        assertThat(exit).isZero();
        assertThat(stdout()).isEqualTo("{\"systemMessage\":\"New suppression added this session: JG-TST-002 at "
                + "src/test/java/com/acme/AppTest.java:6\"}\n");
    }

    private static final String SPRING_POM = CommandTestBase.MINIMAL_POM.replace("</project>",
            "<dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter</artifactId>"
                    + "<version>3.3.4</version></dependency></dependencies></project>");

    private static final String PRIVATE_TRANSACTIONAL = "package com.acme;\n\n"
            + "import org.springframework.transaction.annotation.Transactional;\n\n"
            + "public class App {\n    @Transactional\n    private void save() {\n    }\n}\n";

    @Test
    void aSpringRuleReachesTheModelInAProjectThatDeclaresSpring() throws Exception {
        writeProjectFile("pom.xml", SPRING_POM);
        writeProjectFile(REL, PRIVATE_TRANSACTIONAL);

        int exit = postEdit();

        assertThat(exit).isZero();
        assertThat(stdout()).contains("\"additionalContext\":\"JAVAGUARD ADVISE").contains("JG-SPR-001");
    }

    @Test
    void theSameCodeRaisesNoSpringRuleInAProjectWithoutSpring() throws Exception {
        writeProjectFile(REL, PRIVATE_TRANSACTIONAL);

        int exit = postEdit();

        assertThat(exit).isZero();
        assertThat(stdout()).doesNotContain("JG-SPR-");
    }

    @Test
    void anAgentThatKeepsWritingTheSameBlockedCodeGetsAHintAndIsThenHalted() throws Exception {
        List<com.devmanchego.javaguard.notify.Notification> notified = new java.util.ArrayList<>();
        com.devmanchego.javaguard.notify.Notifiers.overrideForTests(settings -> notified::add);
        try {
            haltScenario(notified);
        } finally {
            com.devmanchego.javaguard.notify.Notifiers.overrideForTests(null);
        }
    }

    private void haltScenario(List<com.devmanchego.javaguard.notify.Notification> notified) throws Exception {
        List<String> stderrs = new java.util.ArrayList<>();
        List<String> stdouts = new java.util.ArrayList<>();
        List<Integer> exits = new java.util.ArrayList<>();
        for (int attempt = 1; attempt <= 6; attempt++) {
            writeProjectFile(REL, SQL_CONCAT.replace("public class App {", "public class App {\n    // attempt " + attempt));
            clearOutput();
            exits.add(postEdit());
            stderrs.add(stderr());
            stdouts.add(stdout());
        }

        assertThat(exits.subList(0, 5)).containsOnly(2);
        assertThat(stderrs.get(1)).doesNotContain("Change of approach required");
        assertThat(stderrs.get(2)).contains("## Change of approach required")
                .contains("You have attempted this 3 times with the same result.");

        // Level 3: Claude is stopped through continue:false on stdout, with the bell, and exit 0 (contracts section 3.3).
        assertThat(exits.get(5)).isZero();
        assertThat(stderrs.get(5)).isEmpty();
        assertThat(stdouts.get(5)).startsWith("{\"continue\":false,\"stopReason\":\"JavaGuard halted the agent: the blocking "
                + "problems persisted for 6 attempts (detector: repeat).")
                .contains("\"terminalSequence\":\"\\u0007\"");

        // The user is told once, and only at the HALT (specification section 16).
        assertThat(notified).singleElement().satisfies(n -> {
            assertThat(n.kind()).isEqualTo(com.devmanchego.javaguard.notify.Notification.Kind.HALT);
            assertThat(n.message()).startsWith("JavaGuard halted claude in ").contains(": JG-SEC-")
                    .contains("Handoff: .javaguard/handoff/HANDOFF-");
        });
    }

    @Test
    void anInternalFailureInTheGateStillFailsOpen() throws Exception {
        writeProjectFile(REL, SQL_CONCAT);
        // A file where the state layout needs a directory breaks JavaGuard's own bookkeeping; the
        // agent must still be released (contracts section 1.5): exit 0, nothing on stderr.
        Files.createDirectories(projectRoot.resolve(".javaguard"));
        Files.writeString(projectRoot.resolve(".javaguard/reports"), "not a directory");

        int exit = postEdit();

        assertThat(exit).isZero();
        assertThat(stderr()).isEmpty();
    }
}
