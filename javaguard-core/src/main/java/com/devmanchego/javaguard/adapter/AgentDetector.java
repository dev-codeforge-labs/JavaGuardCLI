package com.devmanchego.javaguard.adapter;

import com.devmanchego.javaguard.model.AgentKind;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Identifies the agent from the payload when {@code --agent} is omitted
 * (contracts &sect;6). Payload signals always win over environment variables:
 * an agent can inherit another agent's variables from a parent shell.
 */
public final class AgentDetector {

    private static final Set<String> GEMINI_EVENTS = Set.of(
            "BeforeTool", "AfterTool", "BeforeAgent", "AfterAgent",
            "BeforeModel", "AfterModel", "BeforeToolSelection");

    private static final Set<String> SHARED_EVENTS = Set.of("PreToolUse", "PostToolUse", "Stop");

    private AgentDetector() {
    }

    public static Optional<AgentKind> detect(JsonNode payload, Map<String, String> env) {
        String event = PayloadFields.text(payload, "hook_event_name").orElse("");
        String tool = PayloadFields.text(payload, "tool_name").orElse("");

        if ("apply_patch".equals(tool) || payload.hasNonNull("turn_id")) {
            return Optional.of(AgentKind.CODEX);
        }
        if (GEMINI_EVENTS.contains(event)) {
            return Optional.of(AgentKind.GEMINI);
        }
        if (SHARED_EVENTS.contains(event)) {
            if (payload.hasNonNull("tool_use_id")) {
                return Optional.of(AgentKind.CLAUDE);
            }
            // Codex sends "model" on every turn-scoped event; Claude Code only on SessionStart.
            if (payload.hasNonNull("model")) {
                return Optional.of(AgentKind.CODEX);
            }
            return Optional.of(AgentKind.CLAUDE);
        }
        if (isSet(env, "GEMINI_PROJECT_DIR")) {
            return Optional.of(AgentKind.GEMINI);
        }
        if (isSet(env, "CLAUDE_PROJECT_DIR")) {
            return Optional.of(AgentKind.CLAUDE);
        }
        return Optional.empty();
    }

    private static boolean isSet(Map<String, String> env, String name) {
        String value = env.get(name);
        return value != null && !value.isBlank();
    }
}
