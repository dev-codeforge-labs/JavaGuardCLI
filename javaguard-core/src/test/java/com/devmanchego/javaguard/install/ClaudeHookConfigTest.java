package com.devmanchego.javaguard.install;

import com.devmanchego.javaguard.install.ClaudeHookConfig.Gate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 7: {@code install} merge preserves foreign hooks and is idempotent; {@code uninstall} removes only JavaGuard entries. */
class ClaudeHookConfigTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LAUNCHER = ClaudeHookConfig.quoteExecutable("C:\\tools\\javaguard\\javaguard.exe");

    private static ObjectNode parse(String json) throws Exception {
        return (ObjectNode) JSON.readTree(json);
    }

    private static ObjectNode installPostEdit(ObjectNode settings) {
        return ClaudeHookConfig.install(settings, LAUNCHER, List.of(Gate.POST_EDIT), List.of());
    }

    @Test
    void writesTheContractShapeIntoAnEmptyDocument() throws Exception {
        ObjectNode result = installPostEdit(parse("{}"));

        assertThat(result.toString()).isEqualTo("{\"hooks\":{\"PostToolUse\":[{\"matcher\":\"Edit|Write|MultiEdit\","
                + "\"hooks\":[{\"type\":\"command\",\"command\":\"\\\"C:/tools/javaguard/javaguard.exe\\\" hook "
                + "--agent claude --gate post-edit\",\"timeout\":30,"
                + "\"statusMessage\":\"JavaGuard: checking edited Java file...\"}]}]}}");
    }

    @Test
    void preservesForeignHooksUnrelatedSettingsAndKeyOrder() throws Exception {
        ObjectNode existing = parse("""
                {"model":"opus","hooks":{"PostToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"echo hi"}]}],
                 "SessionStart":[{"hooks":[{"type":"command","command":"setup.sh"}]}]},"permissions":{"allow":["Read"]}}""");

        ObjectNode result = installPostEdit(existing);

        assertThat(result.fieldNames()).toIterable().containsExactly("model", "hooks", "permissions");
        assertThat(result.at("/hooks/SessionStart/0/hooks/0/command").asText()).isEqualTo("setup.sh");
        assertThat(result.at("/hooks/PostToolUse")).hasSize(2);
        assertThat(result.at("/hooks/PostToolUse/0/hooks/0/command").asText()).isEqualTo("echo hi");
        assertThat(result.at("/permissions/allow/0").asText()).isEqualTo("Read");
        assertThat(existing.at("/hooks/PostToolUse")).as("the input is not modified").hasSize(1);
    }

    @Test
    void installingTwiceIsIdempotent() throws Exception {
        ObjectNode once = installPostEdit(parse("{\"hooks\":{\"Stop\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"notify.sh\"}]}]}}"));
        ObjectNode twice = installPostEdit(once);

        assertThat(twice).isEqualTo(once);
        assertThat(ClaudeHookConfig.render(twice)).isEqualTo(ClaudeHookConfig.render(once));
    }

    @Test
    void reinstallingWithADifferentLauncherUpdatesInPlaceInsteadOfDuplicating() throws Exception {
        ObjectNode first = installPostEdit(parse("{}"));

        ObjectNode moved = ClaudeHookConfig.install(first, ClaudeHookConfig.quoteExecutable("D:/apps/javaguard.exe"),
                List.of(Gate.POST_EDIT), List.of());

        assertThat(moved.at("/hooks/PostToolUse")).hasSize(1);
        assertThat(moved.at("/hooks/PostToolUse/0/hooks")).hasSize(1);
        assertThat(moved.at("/hooks/PostToolUse/0/hooks/0/command").asText()).startsWith("\"D:/apps/javaguard.exe\"");
    }

    @Test
    void collapsesDuplicatesLeftByAnInterruptedInstall() throws Exception {
        ObjectNode single = installPostEdit(parse("{}"));
        ObjectNode duplicated = single.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) duplicated.at("/hooks/PostToolUse"))
                .add(single.at("/hooks/PostToolUse/0").deepCopy());

        ObjectNode result = installPostEdit(duplicated);

        assertThat(result.at("/hooks/PostToolUse")).hasSize(1);
    }

    @Test
    void preEditIsAddedAndRemovedIndependentlyOfPostEdit() throws Exception {
        ObjectNode both = ClaudeHookConfig.install(parse("{}"), LAUNCHER, List.of(Gate.POST_EDIT, Gate.PRE_EDIT), List.of());
        assertThat(ClaudeHookConfig.installedGates(both)).containsExactlyInAnyOrder(Gate.POST_EDIT, Gate.PRE_EDIT);
        assertThat(both.at("/hooks/PreToolUse/0/hooks/0/command").asText()).endsWith("hook --agent claude --gate pre-edit");

        ObjectNode withoutPre = ClaudeHookConfig.install(both, LAUNCHER, List.of(Gate.POST_EDIT), List.of(Gate.PRE_EDIT));

        assertThat(ClaudeHookConfig.installedGates(withoutPre)).containsExactly(Gate.POST_EDIT);
        assertThat(withoutPre.at("/hooks").has("PreToolUse")).isFalse();
    }

    @Test
    void uninstallRemovesOnlyJavaGuardEntries() throws Exception {
        ObjectNode existing = parse("""
                {"hooks":{"PostToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"echo hi"}]}]}}""");
        ObjectNode installed = ClaudeHookConfig.install(existing, LAUNCHER, List.of(Gate.POST_EDIT, Gate.PRE_EDIT, Gate.END_OF_TURN),
                List.of());

        ObjectNode result = ClaudeHookConfig.uninstall(installed);

        assertThat(result).isEqualTo(existing);
    }

    @Test
    void uninstallKeepsAForeignHookThatSharesAGroupWithJavaGuard() throws Exception {
        ObjectNode installed = installPostEdit(parse("{}"));
        ((com.fasterxml.jackson.databind.node.ArrayNode) installed.at("/hooks/PostToolUse/0/hooks"))
                .addObject().put("type", "command").put("command", "lint.sh");

        ObjectNode result = ClaudeHookConfig.uninstall(installed);

        assertThat(result.at("/hooks/PostToolUse/0/hooks")).hasSize(1);
        assertThat(result.at("/hooks/PostToolUse/0/hooks/0/command").asText()).isEqualTo("lint.sh");
    }

    @Test
    void uninstallOnADocumentWithoutHooksChangesNothing() throws Exception {
        ObjectNode existing = parse("{\"model\":\"opus\"}");

        assertThat(ClaudeHookConfig.uninstall(existing)).isEqualTo(existing);
    }

    @Test
    void aForeignCommandThatMerelyMentionsTheWordsIsNotRecognized() throws Exception {
        ObjectNode existing = parse("""
                {"hooks":{"PostToolUse":[{"hooks":[{"type":"command","command":"my-tool hook --agent codex --gate post-edit"}]}]}}""");

        assertThat(ClaudeHookConfig.installedGates(existing)).isEmpty();
        assertThat(ClaudeHookConfig.uninstall(existing)).isEqualTo(existing);
    }

    @Test
    void renderUsesTwoSpaceIndentAndKeyColonValue() throws Exception {
        String text = ClaudeHookConfig.render(installPostEdit(parse("{}")));

        assertThat(text).startsWith("{\n  \"hooks\": {\n    \"PostToolUse\": [\n      {\n        \"matcher\": \"Edit|Write|MultiEdit\",");
        assertThat(text).endsWith("}\n");
    }

    @Test
    void replacesNonObjectHooksValueWithoutCrashing() throws Exception {
        ObjectNode result = installPostEdit(parse("{\"hooks\":[]}"));

        assertThat(ClaudeHookConfig.installedGates(result)).containsExactly(Gate.POST_EDIT);
    }
}
