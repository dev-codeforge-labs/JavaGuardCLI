package com.devmanchego.javaguard.adapter.codex;

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
import com.devmanchego.javaguard.model.FileChange;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.HookEvent;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Codex hooks: {@code PreToolUse}, {@code PostToolUse}, {@code Stop}
 * (contracts &sect;5). File edits arrive as an {@code apply_patch} envelope
 * with no {@code file_path}; see {@link ApplyPatchParser}. Edits made through
 * shell commands are invisible here and are caught by the end-of-turn gate.
 */
public final class CodexAdapter implements AgentAdapter {

    private static final GateResolver GATES = new GateResolver(AgentKind.CODEX, Map.of(
            "PreToolUse", GateKind.PRE_EDIT,
            "PostToolUse", GateKind.POST_EDIT,
            "Stop", GateKind.END_OF_TURN,
            "UserPromptSubmit", GateKind.TURN_START));

    private static final HookOutputRenderer RENDERER = new HookOutputRenderer(new HookOutputRenderer.Profile(
            "", HookOutputRenderer.BlockChannel.JSON_DECISION, "block", "PostToolUse", false));

    @Override
    public AgentKind kind() {
        return AgentKind.CODEX;
    }

    @Override
    public HookEvent parse(JsonNode payload, ParseContext ctx) throws PayloadException {
        GateKind gate = GATES.resolve(payload, ctx);
        Path cwd = PayloadFields.cwd(payload, ctx);
        String toolName = !gate.concernsTools() ? "" : PayloadFields.text(payload, "tool_name").orElse("");
        List<FileChange> changes = gate.concernsTools() && "apply_patch".equals(toolName)
                ? changes(patchText(payload.get("tool_input")), ctx, cwd)
                : List.of();
        return new HookEvent(
                AgentKind.CODEX,
                gate,
                PayloadFields.sessionId(payload),
                cwd,
                PayloadFields.transcriptPath(payload, ctx, cwd),
                toolName,
                changes,
                PayloadFields.bool(payload, "stop_hook_active"),
                PayloadFields.text(payload, "last_assistant_message"),
                ctx.receivedAt());
    }

    /** {@code tool_input.command} per contract; also tolerates {@code patch}, {@code input} or a bare string. */
    private static String patchText(JsonNode toolInput) {
        if (toolInput == null || toolInput.isNull()) {
            return "";
        }
        if (toolInput.isTextual()) {
            return toolInput.asText();
        }
        return PayloadFields.firstText(toolInput, "command", "patch", "input").orElse("");
    }

    private static List<FileChange> changes(String patch, ParseContext ctx, Path cwd) throws PayloadException {
        List<FileChange> changes = new ArrayList<>();
        for (PatchFileOp op : ApplyPatchParser.parse(patch)) {
            Path path = resolve(op.path(), ctx, cwd);
            if (op.type() == ChangeType.MOVE) {
                Path destination = resolve(op.movedTo().orElseThrow(), ctx, cwd);
                changes.add(new FileChange(destination, ChangeType.MOVE, Optional.of(path), op.fragments()));
            } else {
                changes.add(new FileChange(path, op.type(), Optional.empty(), op.fragments()));
            }
        }
        return changes;
    }

    private static Path resolve(String raw, ParseContext ctx, Path cwd) throws PayloadException {
        try {
            return ctx.paths().resolve(raw, cwd);
        } catch (IllegalArgumentException e) {
            throw new PayloadException("Invalid path in Codex apply_patch: " + raw, e);
        }
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
