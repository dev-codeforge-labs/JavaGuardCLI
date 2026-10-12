package com.devmanchego.javaguard.adapter.claude;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Claude Code hooks: {@code PreToolUse}, {@code PostToolUse}, {@code Stop}
 * (contracts &sect;3).
 */
public final class ClaudeAdapter implements AgentAdapter {

    private static final GateResolver GATES = new GateResolver(AgentKind.CLAUDE, Map.of(
            "PreToolUse", GateKind.PRE_EDIT,
            "PostToolUse", GateKind.POST_EDIT,
            "Stop", GateKind.END_OF_TURN,
            "UserPromptSubmit", GateKind.TURN_START));

    private static final HookOutputRenderer RENDERER = new HookOutputRenderer(new HookOutputRenderer.Profile(
            "", HookOutputRenderer.BlockChannel.EXIT_2_STDERR, "block", "PostToolUse", true));

    @Override
    public AgentKind kind() {
        return AgentKind.CLAUDE;
    }

    @Override
    public HookEvent parse(JsonNode payload, ParseContext ctx) throws PayloadException {
        GateKind gate = GATES.resolve(payload, ctx);
        Path cwd = PayloadFields.cwd(payload, ctx);
        String toolName = !gate.concernsTools() ? "" : PayloadFields.text(payload, "tool_name").orElse("");
        List<FileChange> changes = !gate.concernsTools() ? List.of() : changes(payload, toolName, ctx, cwd);
        return new HookEvent(
                AgentKind.CLAUDE,
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

    private static List<FileChange> changes(JsonNode payload, String toolName, ParseContext ctx, Path cwd)
            throws PayloadException {
        JsonNode input = payload.path("tool_input");
        Optional<String> filePath = PayloadFields.text(input, "file_path");
        if (filePath.isEmpty()) {
            return List.of();
        }
        Path path = resolve(filePath.get(), ctx, cwd);
        return switch (toolName) {
            case "Write" -> List.of(FileChange.rewrite(path, writeType(payload)));
            case "Edit" -> List.of(FileChange.edit(path, List.of(fragment(input))));
            case "MultiEdit" -> List.of(FileChange.edit(path, multiEditFragments(input)));
            default -> List.of();
        };
    }

    /** {@code tool_response.type == "create"} marks a new file; anything else is an update. */
    private static ChangeType writeType(JsonNode payload) {
        String type = PayloadFields.text(payload.path("tool_response"), "type").orElse("");
        return "create".equals(type) ? ChangeType.ADD : ChangeType.UPDATE;
    }

    private static EditFragment fragment(JsonNode edit) {
        return new EditFragment(
                PayloadFields.rawText(edit, "old_string").orElse(""),
                PayloadFields.rawText(edit, "new_string").orElse(""),
                PayloadFields.bool(edit, "replace_all"));
    }

    private static List<EditFragment> multiEditFragments(JsonNode input) {
        List<EditFragment> fragments = new ArrayList<>();
        JsonNode edits = input.path("edits");
        if (edits.isArray()) {
            for (JsonNode edit : edits) {
                fragments.add(fragment(edit));
            }
        }
        return fragments;
    }

    private static Path resolve(String raw, ParseContext ctx, Path cwd) throws PayloadException {
        try {
            return ctx.paths().resolve(raw, cwd);
        } catch (IllegalArgumentException e) {
            throw new PayloadException("Invalid file_path in Claude payload: " + raw, e);
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
