package com.devmanchego.javaguard.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Agent-independent view of one hook invocation (contracts &sect;2). Every
 * {@code AgentAdapter} parses its payload into this record; nothing
 * downstream of the adapter knows which agent is on the other side, except
 * through {@link #agent()} for reporting.
 *
 * @param agent                agent that sent the payload
 * @param gate                 gate to run, derived from the payload's event name
 * @param sessionId            agent session id; {@value #UNKNOWN_SESSION} when absent
 * @param cwd                  absolute working directory reported by the agent
 * @param transcriptPath       agent transcript, when provided
 * @param toolName             raw tool name; empty for END_OF_TURN
 * @param changes              files changed by the tool call; empty for END_OF_TURN,
 *                             for failed tool calls and for tools that touch no file
 * @param stopHookActive       true when the agent is already continuing because
 *                             of a previous end-of-turn block
 * @param lastAssistantMessage the agent's final message of the turn, when provided
 * @param receivedAt           when JavaGuard received the payload
 */
public record HookEvent(
        AgentKind agent,
        GateKind gate,
        String sessionId,
        Path cwd,
        Optional<Path> transcriptPath,
        String toolName,
        List<FileChange> changes,
        boolean stopHookActive,
        Optional<String> lastAssistantMessage,
        Instant receivedAt
) {

    public static final String UNKNOWN_SESSION = "unknown";

    public HookEvent {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(transcriptPath, "transcriptPath");
        Objects.requireNonNull(toolName, "toolName");
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        Objects.requireNonNull(lastAssistantMessage, "lastAssistantMessage");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (!cwd.isAbsolute()) {
            throw new IllegalArgumentException("HookEvent cwd must be absolute: " + cwd);
        }
    }
}
