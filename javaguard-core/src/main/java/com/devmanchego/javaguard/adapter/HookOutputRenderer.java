package com.devmanchego.javaguard.adapter;

import com.devmanchego.javaguard.model.AgentFeedback;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.Verdict;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Renders {@link AgentFeedback} for the three supported agents, which differ
 * only in a handful of channel choices captured by {@link Profile}.
 *
 * <p>JSON key order is fixed and part of the contract (deterministic output):
 * {@code continue, stopReason, decision, reason, systemMessage,
 * terminalSequence, hookSpecificOutput}.
 */
public final class HookOutputRenderer {

    static final String INTERNAL_ERROR_MESSAGE =
            "JavaGuard internal error, check .javaguard/logs/javaguard.log";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** How a BLOCK verdict reaches the model. */
    public enum BlockChannel {
        /** Exit code 2, report on stderr (Claude Code). */
        EXIT_2_STDERR,
        /** Exit code 0, JSON {@code decision}/{@code reason} on stdout (Gemini CLI, Codex). */
        JSON_DECISION
    }

    /**
     * Agent-specific channel choices.
     *
     * @param passStdout             stdout for PASS ({@code ""} or {@code "{}"})
     * @param blockChannel           how BLOCK is delivered
     * @param blockDecision          value of {@code decision} for {@link BlockChannel#JSON_DECISION}
     * @param postEditEventName      {@code hookEventName} inside {@code hookSpecificOutput} for POST_EDIT
     * @param supportsTerminalBell   whether {@code terminalSequence} is understood
     */
    public record Profile(
            String passStdout,
            BlockChannel blockChannel,
            String blockDecision,
            String postEditEventName,
            boolean supportsTerminalBell
    ) {
        public Profile {
            Objects.requireNonNull(passStdout, "passStdout");
            Objects.requireNonNull(blockChannel, "blockChannel");
            Objects.requireNonNull(blockDecision, "blockDecision");
            Objects.requireNonNull(postEditEventName, "postEditEventName");
        }
    }

    private final Profile profile;

    public HookOutputRenderer(Profile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    public AgentResponse render(GateKind gate, AgentFeedback feedback) {
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(feedback, "feedback");
        if (gate == GateKind.PRE_EDIT || gate == GateKind.TURN_START) {
            return pass(); // Nothing to say: on Claude Code and Codex, a UserPromptSubmit output would be added to the prompt.
        }
        Fields fields = new Fields();
        Optional<String> notices = joinedNotices(feedback);
        if (feedback.verdict() == Verdict.PASS) {
            // Nothing to fix, but the user must still hear about suppressions the agent added (specification
            // section 11.3): an agent that silences the only finding passes, and that is exactly when it matters.
            if (notices.isEmpty()) {
                return pass();
            }
            fields.systemMessage = notices.get();
            return new AgentResponse(json(fields), "", 0);
        }
        String bell = profile.supportsTerminalBell() && feedback.ringBell() ? "\u0007" : null;

        return switch (feedback.verdict()) {
            case HALT -> {
                fields.continueFlag = Boolean.FALSE;
                fields.stopReason = feedback.haltMessage();
                fields.systemMessage = concat(Optional.of(feedback.haltMessage()), notices);
                fields.terminalSequence = bell;
                yield new AgentResponse(json(fields), "", 0);
            }
            case BLOCK -> {
                fields.systemMessage = notices.orElse(null);
                fields.terminalSequence = bell;
                if (profile.blockChannel() == BlockChannel.EXIT_2_STDERR) {
                    String stdout = fields.isEmpty() ? "" : json(fields);
                    yield new AgentResponse(stdout, withNewline(feedback.report()), 2);
                }
                fields.decision = profile.blockDecision();
                fields.reason = feedback.report();
                yield new AgentResponse(json(fields), "", 0);
            }
            case ADVISE -> {
                fields.terminalSequence = bell;
                if (gate == GateKind.POST_EDIT) {
                    fields.systemMessage = notices.orElse(null);
                    fields.additionalContext = feedback.report();
                } else {
                    // End-of-turn advisories go to the user, not the model (spec section 10.3, Q1).
                    fields.systemMessage = concat(Optional.of(feedback.effectiveSummary()), notices);
                }
                yield new AgentResponse(json(fields), "", 0);
            }
            case PASS -> pass();
        };
    }

    public AgentResponse renderInternalError() {
        Fields fields = new Fields();
        fields.systemMessage = INTERNAL_ERROR_MESSAGE;
        return new AgentResponse(json(fields), "", 0);
    }

    private AgentResponse pass() {
        return new AgentResponse(profile.passStdout().isEmpty() ? "" : withNewline(profile.passStdout()), "", 0);
    }

    private static Optional<String> joinedNotices(AgentFeedback feedback) {
        List<String> notices = new ArrayList<>();
        for (String n : feedback.userNotices()) {
            if (n != null && !n.isBlank()) {
                notices.add(n.strip());
            }
        }
        return notices.isEmpty() ? Optional.empty() : Optional.of(String.join("\n", notices));
    }

    private static String concat(Optional<String> first, Optional<String> second) {
        List<String> parts = new ArrayList<>();
        first.filter(s -> !s.isBlank()).ifPresent(parts::add);
        second.filter(s -> !s.isBlank()).ifPresent(parts::add);
        return parts.isEmpty() ? null : String.join("\n", parts);
    }

    private static String withNewline(String s) {
        return s.endsWith("\n") ? s : s + "\n";
    }

    private String json(Fields f) {
        ObjectNode node = MAPPER.createObjectNode();
        if (f.continueFlag != null) {
            node.put("continue", f.continueFlag);
        }
        putIfPresent(node, "stopReason", f.stopReason);
        putIfPresent(node, "decision", f.decision);
        putIfPresent(node, "reason", f.reason);
        putIfPresent(node, "systemMessage", f.systemMessage);
        putIfPresent(node, "terminalSequence", f.terminalSequence);
        if (f.additionalContext != null) {
            ObjectNode specific = node.putObject("hookSpecificOutput");
            specific.put("hookEventName", profile.postEditEventName());
            specific.put("additionalContext", f.additionalContext);
        }
        try {
            return MAPPER.writeValueAsString(node) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize hook output", e);
        }
    }

    private static void putIfPresent(ObjectNode node, String name, String value) {
        if (value != null) {
            node.put(name, value);
        }
    }

    /** Mutable scratch holder; serialized in canonical key order by {@link #json}. */
    private static final class Fields {
        Boolean continueFlag;
        String stopReason;
        String decision;
        String reason;
        String systemMessage;
        String terminalSequence;
        String additionalContext;

        boolean isEmpty() {
            return continueFlag == null && stopReason == null && decision == null && reason == null
                    && systemMessage == null && terminalSequence == null && additionalContext == null;
        }
    }
}
