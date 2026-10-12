package com.devmanchego.javaguard.adapter;

import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.model.GateKind;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Maps an agent's {@code hook_event_name} to a {@link GateKind}.
 *
 * <p>The payload's event name wins over {@code --gate}: the output format
 * must match the event the agent actually fired, whatever the hook
 * configuration says. {@code --gate} is only a fallback for payloads with a
 * missing or unrecognized event name.
 */
public final class GateResolver {

    private static final Logger LOG = Logger.getLogger(GateResolver.class.getName());

    private final AgentKind agent;
    private final Map<String, GateKind> events;

    public GateResolver(AgentKind agent, Map<String, GateKind> events) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.events = Map.copyOf(events);
    }

    public GateKind resolve(JsonNode payload, ParseContext ctx) throws PayloadException {
        Optional<String> event = PayloadFields.text(payload, "hook_event_name");
        Optional<GateKind> fromPayload = event.map(events::get);
        if (fromPayload.isPresent()) {
            ctx.requestedGate()
                    .filter(requested -> requested != fromPayload.get())
                    .ifPresent(requested -> LOG.warning(() -> agent + " sent " + event.get()
                            + " but the hook was configured with --gate " + requested.cliName()
                            + "; using " + fromPayload.get().cliName()));
            return fromPayload.get();
        }
        if (ctx.requestedGate().isPresent()) {
            return ctx.requestedGate().get();
        }
        throw new PayloadException(agent + " payload has "
                + event.map(e -> "unsupported hook_event_name '" + e + "'").orElse("no hook_event_name")
                + " and no --gate was given");
    }
}
