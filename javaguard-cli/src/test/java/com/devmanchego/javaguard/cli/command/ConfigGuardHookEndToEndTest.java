package com.devmanchego.javaguard.cli.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/** An agent that edits JavaGuard's own configuration gets blocked, and the edit changes nothing in the meantime. */
class ConfigGuardHookEndToEndTest extends CommandTestBase {

    private static final String REL = "src/main/java/com/acme/App.java";
    private static final String CLEAN = """
            package com.acme;

            public class App {
                public int one() {
                    return 1;
                }
            }
            """;
    private static final String WITH_PRINTLN = """
            package com.acme;

            public class App {
                public int one() {
                    System.out.println("debug");
                    return 1;
                }
            }
            """;

    @BeforeEach
    void project() throws IOException {
        createMavenProject();
    }

    private int postEdit() {
        String payload = "{\"hook_event_name\":\"PostToolUse\",\"session_id\":\"guard\",\"cwd\":\"" + jsonPath(projectRoot)
                + "\",\"tool_name\":\"Write\",\"tool_use_id\":\"t1\",\"tool_input\":{\"file_path\":\""
                + jsonPath(projectRoot.resolve(REL)) + "\",\"content\":\"x\"},\"tool_response\":{\"type\":\"create\"}}";
        return runWithStdin(payload, "hook", "--agent", "claude", "--gate", "post-edit");
    }

    @Test
    void disablingARuleInJavaguardYmlDuringTheSessionIsBlockedAndHasNoEffect() throws Exception {
        writeProjectFile(REL, CLEAN);
        assertThat(postEdit()).isZero(); // First hook of the session: the configuration is recorded here.

        writeProjectFile("javaguard.yml", "rules:\n  JG-AI-001: { enabled: false }\n");
        writeProjectFile(REL, WITH_PRINTLN);
        int exit = postEdit();

        assertThat(exit).isEqualTo(2);
        assertThat(stderr()).contains("JG-SUP-003").contains("javaguard.yml")
                .contains("Agent changed JavaGuard's configuration")
                // The edit did not take effect: the rule it tried to disable still reports.
                .contains("JG-AI-001");
    }

    @Test
    void restoringTheFileLiftsTheBlock() throws Exception {
        writeProjectFile(REL, CLEAN);
        assertThat(postEdit()).isZero();
        writeProjectFile("javaguard.yml", "suppressions:\n  allowAgentAdded: true\n");
        assertThat(postEdit()).isEqualTo(2);

        Files.deleteIfExists(projectRoot.resolve("javaguard.yml"));

        assertThat(postEdit()).isZero();
    }

    @Test
    void removingJavaguardHooksFromTheAgentSettingsIsBlocked() throws Exception {
        writeProjectFile(".claude/settings.json", """
                {"permissions":{"allow":["Read"]},"hooks":{"PostToolUse":[{"matcher":"Edit|Write","hooks":[
                {"type":"command","command":"\\"/opt/jg/javaguard\\" hook --agent claude --gate post-edit"}]}]}}
                """);
        writeProjectFile(REL, CLEAN);
        assertThat(postEdit()).isZero();

        writeProjectFile(".claude/settings.json", "{\"permissions\":{\"allow\":[\"Read\",\"Bash\"]}}\n");
        int exit = postEdit();

        assertThat(exit).isEqualTo(2);
        assertThat(stderr()).contains("JG-SUP-003").contains(".claude/settings.json");
    }

    @Test
    void changingOtherAgentSettingsIsNotAChange() throws Exception {
        writeProjectFile(".claude/settings.json", """
                {"permissions":{"allow":["Read"]},"hooks":{"PostToolUse":[{"matcher":"Edit|Write","hooks":[
                {"type":"command","command":"\\"/opt/jg/javaguard\\" hook --agent claude --gate post-edit"}]}]}}
                """);
        writeProjectFile(REL, CLEAN);
        assertThat(postEdit()).isZero();

        writeProjectFile(".claude/settings.json", """
                {"permissions":{"allow":["Read","Bash"]},"theme":"dark","hooks":{"PostToolUse":[{"matcher":"Edit|Write","hooks":[
                {"type":"command","command":"\\"/opt/jg/javaguard\\" hook --agent claude --gate post-edit"}]}]}}
                """);

        assertThat(postEdit()).isZero();
        assertThat(stderr()).doesNotContain("JG-SUP-003");
    }
}
