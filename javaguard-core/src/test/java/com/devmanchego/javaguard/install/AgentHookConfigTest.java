package com.devmanchego.javaguard.install;

import com.devmanchego.javaguard.install.AgentHookConfig.Agent;
import com.devmanchego.javaguard.install.AgentHookConfig.Gate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 16: the merges into {@code .gemini/settings.json} and {@code .codex/hooks.json} (contracts sections 4.4 and 5.4). */
class AgentHookConfigTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EXE = "\"C:/tools/javaguard/javaguard.exe\"";
    private static final List<Gate> ALL = List.of(Gate.PRE_EDIT, Gate.POST_EDIT, Gate.END_OF_TURN);

    private static ObjectNode parse(String json) throws Exception {
        return (ObjectNode) JSON.readTree(json);
    }

    @Test
    void geminiGetsAfterToolAndAfterAgentWithMillisecondTimeouts() throws Exception {
        ObjectNode result = AgentHookConfig.install(JSON.createObjectNode(), Agent.GEMINI, EXE, Optional.empty(),
                List.of(Gate.POST_EDIT, Gate.END_OF_TURN), List.of());

        JsonNode post = result.at("/hooks/AfterTool/0");
        assertThat(post.get("matcher").asText()).isEqualTo("write_file|replace");
        assertThat(post.at("/hooks/0/name").asText()).isEqualTo("javaguard-post-edit");
        assertThat(post.at("/hooks/0/command").asText()).isEqualTo(EXE + " hook --agent gemini --gate post-edit");
        assertThat(post.at("/hooks/0/timeout").asInt()).isEqualTo(30_000);
        JsonNode stop = result.at("/hooks/AfterAgent/0");
        assertThat(stop.has("matcher")).isFalse();
        assertThat(stop.at("/hooks/0/timeout").asInt()).isEqualTo(600_000);
        assertThat(result.at("/hooks").has("BeforeTool")).isFalse();
    }

    @Test
    void geminiPreEditUsesBeforeTool() {
        ObjectNode result = AgentHookConfig.install(JSON.createObjectNode(), Agent.GEMINI, EXE, Optional.empty(), ALL, List.of());

        assertThat(result.at("/hooks/BeforeTool/0/hooks/0/timeout").asInt()).isEqualTo(10_000);
    }

    @Test
    void codexGetsPostToolUseAndStopWithSecondTimeoutsAndAnApplyPatchMatcher() {
        ObjectNode result = AgentHookConfig.install(JSON.createObjectNode(), Agent.CODEX, EXE, Optional.empty(),
                List.of(Gate.POST_EDIT, Gate.END_OF_TURN), List.of());

        JsonNode post = result.at("/hooks/PostToolUse/0");
        assertThat(post.get("matcher").asText()).isEqualTo("^apply_patch$");
        assertThat(post.at("/hooks/0/command").asText()).isEqualTo(EXE + " hook --agent codex --gate post-edit");
        assertThat(post.at("/hooks/0/timeout").asInt()).isEqualTo(30);
        assertThat(post.at("/hooks/0/statusMessage").asText()).startsWith("JavaGuard");
        assertThat(post.at("/hooks/0").has("commandWindows")).isFalse();
        assertThat(result.at("/hooks/Stop/0/hooks/0/timeout").asInt()).isEqualTo(600);
    }

    @Test
    void codexOnWindowsWritesAPortableCommandAndACommandWindows() {
        ObjectNode result = AgentHookConfig.install(JSON.createObjectNode(), Agent.CODEX, EXE, Optional.of(EXE),
                List.of(Gate.END_OF_TURN), List.of());

        JsonNode hook = result.at("/hooks/Stop/0/hooks/0");
        assertThat(hook.get("command").asText()).isEqualTo("javaguard hook --agent codex --gate end-of-turn");
        assertThat(hook.get("commandWindows").asText()).isEqualTo(EXE + " hook --agent codex --gate end-of-turn");
    }

    @Test
    void installingTwiceGivesTheSameDocumentAndForeignHooksSurvive() throws Exception {
        ObjectNode foreign = parse("{\"theme\":\"dark\",\"hooks\":{\"AfterTool\":[{\"matcher\":\"shell\",\"hooks\":"
                + "[{\"name\":\"mine\",\"type\":\"command\",\"command\":\"echo hi\"}]}]}}");

        ObjectNode once = AgentHookConfig.install(foreign, Agent.GEMINI, EXE, Optional.empty(), ALL, List.of());
        ObjectNode twice = AgentHookConfig.install(once, Agent.GEMINI, EXE, Optional.empty(), ALL, List.of());

        assertThat(twice).isEqualTo(once);
        assertThat(once.get("theme").asText()).isEqualTo("dark");
        assertThat(once.at("/hooks/AfterTool/0/hooks/0/command").asText()).isEqualTo("echo hi");
        assertThat(once.at("/hooks/AfterTool")).hasSize(2);
        assertThat(foreign.at("/hooks/AfterTool")).hasSize(1); // the input is not modified
    }

    @Test
    void aMovedLauncherIsUpdatedInPlaceNotDuplicated() {
        ObjectNode first = AgentHookConfig.install(JSON.createObjectNode(), Agent.CODEX, "\"/old/javaguard\"", Optional.empty(), ALL, List.of());

        ObjectNode moved = AgentHookConfig.install(first, Agent.CODEX, EXE, Optional.empty(), ALL, List.of());

        assertThat(moved.at("/hooks/PostToolUse")).hasSize(1);
        assertThat(moved.at("/hooks/PostToolUse/0/hooks/0/command").asText()).startsWith(EXE);
    }

    @Test
    void uninstallRemovesOnlyJavaGuardEntriesAndEmptyContainersGoAway() throws Exception {
        ObjectNode foreign = parse("{\"hooks\":{\"Stop\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"notify-me\"}]}]}}");
        ObjectNode installed = AgentHookConfig.install(foreign, Agent.CODEX, EXE, Optional.of(EXE), ALL, List.of());

        ObjectNode removed = AgentHookConfig.uninstall(installed, Agent.CODEX);

        assertThat(removed).isEqualTo(foreign);
        assertThat(AgentHookConfig.uninstall(AgentHookConfig.install(JSON.createObjectNode(), Agent.GEMINI, EXE, Optional.empty(), ALL,
                List.of()), Agent.GEMINI)).isEqualTo(JSON.createObjectNode());
    }

    @Test
    void geminiOnWindowsGetsTheCallOperatorBecauseItRunsHooksThroughPowerShell() {
        String launcher = AgentHookConfig.launcherFor(Agent.GEMINI, EXE, true);
        ObjectNode settings = AgentHookConfig.install(JSON.createObjectNode(), Agent.GEMINI, launcher, Optional.empty(), ALL, List.of());

        assertThat(launcher).isEqualTo("& " + EXE);
        for (Gate gate : ALL) {
            String command = settings.path("hooks").path(gate.event(Agent.GEMINI)).path(0).path("hooks").path(0).path("command").asText();
            assertThat(command).isEqualTo("& " + EXE + " hook --agent gemini --gate " + gate.cliName());
        }
        assertThat(AgentHookConfig.hasGeminiCommandWithoutCallOperator(settings)).isFalse();
        assertThat(AgentHookConfig.installedGates(settings, Agent.GEMINI)).containsExactlyElementsOf(ALL);
    }

    @Test
    void otherAgentsAndPlatformsKeepTheLauncherAsItIs() {
        assertThat(AgentHookConfig.launcherFor(Agent.GEMINI, EXE, false)).isEqualTo(EXE);
        assertThat(AgentHookConfig.launcherFor(Agent.CODEX, EXE, false)).isEqualTo(EXE);
    }

    @Test
    void codexOnWindowsGetsTheCallOperatorInCommandWindowsAndAPortableCommandWithout() {
        String windowsLauncher = AgentHookConfig.launcherFor(Agent.CODEX, EXE, true);
        ObjectNode settings = AgentHookConfig.install(JSON.createObjectNode(), Agent.CODEX, EXE, Optional.of(windowsLauncher),
                List.of(Gate.POST_EDIT, Gate.END_OF_TURN), List.of());

        JsonNode hook = settings.at("/hooks/Stop/0/hooks/0");
        assertThat(windowsLauncher).isEqualTo("& " + EXE);
        assertThat(hook.get("command").asText()).isEqualTo("javaguard hook --agent codex --gate end-of-turn");
        assertThat(hook.get("commandWindows").asText()).isEqualTo("& " + EXE + " hook --agent codex --gate end-of-turn");
        assertThat(AgentHookConfig.hasWindowsCommandWithoutCallOperator(settings, Agent.CODEX)).isFalse();

        // What an earlier version wrote (Codex reported "hook exited with code 1"): detected, and rewritten without duplicates.
        ObjectNode old = AgentHookConfig.install(JSON.createObjectNode(), Agent.CODEX, EXE, Optional.of(EXE),
                List.of(Gate.POST_EDIT, Gate.END_OF_TURN), List.of());
        assertThat(AgentHookConfig.hasWindowsCommandWithoutCallOperator(old, Agent.CODEX)).isTrue();
        ObjectNode fixed = AgentHookConfig.install(old, Agent.CODEX, EXE, Optional.of(windowsLauncher),
                List.of(Gate.POST_EDIT, Gate.END_OF_TURN), List.of());
        assertThat(AgentHookConfig.hasWindowsCommandWithoutCallOperator(fixed, Agent.CODEX)).isFalse();
        assertThat(fixed.at("/hooks/Stop")).hasSize(1);
        assertThat(fixed.at("/hooks/Stop/0/hooks")).hasSize(1);
    }

    @Test
    void reinstallingRewritesTheOldGeminiCommandsWithoutDuplicatingThem() {
        ObjectNode old = AgentHookConfig.install(JSON.createObjectNode(), Agent.GEMINI, EXE, Optional.empty(), ALL, List.of());
        assertThat(AgentHookConfig.hasGeminiCommandWithoutCallOperator(old)).isTrue();

        ObjectNode fixed = AgentHookConfig.install(old, Agent.GEMINI, AgentHookConfig.launcherFor(Agent.GEMINI, EXE, true),
                Optional.empty(), ALL, List.of());

        assertThat(AgentHookConfig.hasGeminiCommandWithoutCallOperator(fixed)).isFalse();
        assertThat(fixed.path("hooks").path("AfterTool")).hasSize(1);
        assertThat(fixed.path("hooks").path("AfterTool").path(0).path("hooks")).hasSize(1);
    }

    @Test
    void agentsDoNotTouchEachOthersEntries() {
        ObjectNode gemini = AgentHookConfig.install(JSON.createObjectNode(), Agent.GEMINI, EXE, Optional.empty(), ALL, List.of());

        assertThat(AgentHookConfig.installedGates(gemini, Agent.CODEX)).isEmpty();
        assertThat(AgentHookConfig.installedGates(gemini, Agent.GEMINI)).containsExactlyElementsOf(ALL);
        assertThat(AgentHookConfig.uninstall(gemini, Agent.CODEX)).isEqualTo(gemini);
    }

    @Test
    void removingAGateLeavesTheOthers() {
        ObjectNode all = AgentHookConfig.install(JSON.createObjectNode(), Agent.GEMINI, EXE, Optional.empty(), ALL, List.of());

        ObjectNode withoutPre = AgentHookConfig.install(all, Agent.GEMINI, EXE, Optional.empty(), List.of(), List.of(Gate.PRE_EDIT));

        assertThat(AgentHookConfig.installedGates(withoutPre, Agent.GEMINI)).containsExactly(Gate.POST_EDIT, Gate.END_OF_TURN);
    }
}
