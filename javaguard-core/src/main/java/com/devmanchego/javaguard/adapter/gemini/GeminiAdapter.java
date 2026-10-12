package com.devmanchego.javaguard.adapter.gemini;

import com.devmanchego.javaguard.adapter.AgentAdapter;
import com.devmanchego.javaguard.adapter.AgentResponse;
import com.devmanchego.javaguard.adapter.GateResolver;
import com.devmanchego.javaguard.adapter.HookOutputRenderer;
import com.devmanchego.javaguard.adapter.ParseContext;
import com.devmanchego.javaguard.adapter.PayloadException;
import com.devmanchego.javaguard.adapter.PayloadFields;
import com.devmanchego.javaguard.model.AgentFeedback;
import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.model.ChangeType;
import com.devmanchego.javaguard.model.EditFragment;
import com.devmanchego.javaguard.model.FileChange;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.HookEvent;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gemini CLI hooks: {@code BeforeTool}, {@code AfterTool}, {@code AfterAgent}
 * (contracts &sect;4). Gemini parses stdout as JSON, so every response,
 * including PASS, is exactly one JSON document and the exit code is always 0.
 */
public final class GeminiAdapter implements AgentAdapter {

    private static final GateResolver GATES = new GateResolver(AgentKind.GEMINI, Map.of(
            "BeforeTool", GateKind.PRE_EDIT,
            "AfterTool", GateKind.POST_EDIT,
            "AfterAgent", GateKind.END_OF_TURN,
            "BeforeAgent", GateKind.TURN_START));

    private static final HookOutputRenderer RENDERER = new HookOutputRenderer(new HookOutputRenderer.Profile(
            "{}", HookOutputRenderer.BlockChannel.JSON_DECISION, "deny", "AfterTool", false));

    @Override
    public AgentKind kind() {
        return AgentKind.GEMINI;
    }

    @Override
    public HookEvent parse(JsonNode payload, ParseContext ctx) throws PayloadException {
        GateKind gate = GATES.resolve(payload, ctx);
        Path cwd = PayloadFields.cwd(payload, ctx);
        String toolName = !gate.concernsTools() ? "" : PayloadFields.text(payload, "tool_name").orElse("");
        List<FileChange> changes = !gate.concernsTools() ? List.of() : changes(payload, toolName, ctx, cwd);
        return new HookEvent(
                AgentKind.GEMINI,
                gate,
                PayloadFields.sessionId(payload),
                cwd,
                PayloadFields.transcriptPath(payload, ctx, cwd),
                toolName,
                changes,
                PayloadFields.bool(payload, "stop_hook_active"),
                PayloadFields.text(payload, "prompt_response"),
                ctx.receivedAt());
    }

    private static List<FileChange> changes(JsonNode payload, String toolName, ParseContext ctx, Path cwd)
            throws PayloadException {
        if (toolFailed(payload)) {
            return List.of();
        }
        JsonNode input = payload.path("tool_input");
        // Key name to be confirmed with captured payloads (contracts section 4.2); aliases accepted.
        Optional<String> filePath = PayloadFields.firstText(input, "file_path", "absolute_path", "path");
        if (filePath.isEmpty()) {
            return List.of();
        }
        Path path;
        try {
            path = ctx.paths().resolve(filePath.get(), cwd);
        } catch (IllegalArgumentException e) {
            throw new PayloadException("Invalid file path in Gemini payload: " + filePath.get(), e);
        }
        return switch (toolName) {
            case "write_file" -> List.of(FileChange.rewrite(path, ChangeType.UPDATE));
            case "replace" -> List.of(FileChange.edit(path, List.of(new EditFragment(
                    PayloadFields.rawText(input, "old_string").orElse(""),
                    PayloadFields.rawText(input, "new_string").orElse(""),
                    false))));
            default -> List.of();
        };
    }

    /** {@code tool_response.error} present means the edit did not happen (contracts section 4.2). */
    private static boolean toolFailed(JsonNode payload) {
        JsonNode error = payload.path("tool_response").get("error");
        return error != null && !error.isNull();
    }

    @Override
    public AgentResponse render(GateKind gate, AgentFeedback feedback) {
        return RENDERER.render(gate, feedback);
    }

    @Override
    public AgentResponse renderInternalError() {
        return RENDERER.renderInternalError();
    }
}
