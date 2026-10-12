package com.devmanchego.javaguard.model;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of one gate run (specification &sect;6.3). Gates produce this; the
 * report renderer (Phase 3) turns it into an {@link AgentFeedback}; adapters
 * turn that into the agent's wire format.
 *
 * @param solution text of a second model's proposal picked up for this run (specification &sect;14.4); it is
 *                 delivered to the agent whatever the verdict
 */
public record GateResult(
        GateKind gate,
        Verdict verdict,
        List<Finding> blocking,
        List<Finding> advisory,
        List<String> notes,
        Optional<Escalation> escalation,
        Optional<Path> fullReport,
        List<Suppression> newSuppressions,
        Duration elapsed,
        Optional<String> solution
) {

    /** A result without a second opinion (the shape every gate produced before Phase 13). */
    public GateResult(GateKind gate, Verdict verdict, List<Finding> blocking, List<Finding> advisory, List<String> notes,
                      Optional<Escalation> escalation, Optional<Path> fullReport, List<Suppression> newSuppressions,
                      Duration elapsed) {
        this(gate, verdict, blocking, advisory, notes, escalation, fullReport, newSuppressions, elapsed, Optional.empty());
    }

    public GateResult {
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(verdict, "verdict");
        blocking = List.copyOf(Objects.requireNonNull(blocking, "blocking"));
        advisory = List.copyOf(Objects.requireNonNull(advisory, "advisory"));
        notes = List.copyOf(Objects.requireNonNull(notes, "notes"));
        Objects.requireNonNull(escalation, "escalation");
        Objects.requireNonNull(fullReport, "fullReport");
        newSuppressions = List.copyOf(Objects.requireNonNull(newSuppressions, "newSuppressions"));
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(solution, "solution");
        if (verdict == Verdict.BLOCK && blocking.isEmpty()) {
            throw new IllegalArgumentException("BLOCK requires at least one blocking finding");
        }
        if (verdict == Verdict.PASS && (!blocking.isEmpty() || !advisory.isEmpty())) {
            throw new IllegalArgumentException("PASS cannot carry findings");
        }
    }
}
