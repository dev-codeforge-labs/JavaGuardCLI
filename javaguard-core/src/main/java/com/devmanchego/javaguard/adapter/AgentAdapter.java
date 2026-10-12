package com.devmanchego.javaguard.adapter;

import com.devmanchego.javaguard.model.AgentFeedback;
import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.HookEvent;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Translates between one AI CLI's hook protocol and JavaGuard's
 * agent-independent model (.private/spec/03-HOOK-CONTRACTS.md).
 *
 * <p>Implementations are stateless and thread-safe.
 */
public interface AgentAdapter {

    AgentKind kind();

    /**
     * Payload to {@link HookEvent}. Unknown fields are ignored; missing
     * optional fields get defaults.
     *
     * @throws PayloadException when the gate cannot be determined or a
     *                          required value is malformed
     */
    HookEvent parse(JsonNode payload, ParseContext context) throws PayloadException;

    /**
     * Gate outcome to the agent's wire format. At {@link GateKind#PRE_EDIT}
     * every verdict renders as PASS (pre-edit only takes snapshots).
     */
    AgentResponse render(GateKind gate, AgentFeedback feedback);

    /**
     * Fail-open response for an internal error: exit 0, and a message to the
     * user where the agent supports one (contracts &sect;1.5).
     */
    AgentResponse renderInternalError();
}
