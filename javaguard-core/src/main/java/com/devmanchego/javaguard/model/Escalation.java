package com.devmanchego.javaguard.model;

import java.util.Objects;

/**
 * Loop-detection outcome attached to a gate result (specification &sect;13.3).
 *
 * @param level    1 = strategy hint, 2 = consultation, 3 = halt
 * @param detector detector behind the level: {@code repeat}, {@code oscillation}, {@code no-progress},
 *                 {@code stop-guard}, {@code max-blocked-runs}, {@code consultation-failed}
 * @param attempts blocked runs in the current episode, this one included
 * @param guidance agent-facing text that goes with the level (the strategy hint, the consultation notice, the
 *                 instruction to stop); may be empty
 * @param handoff  project-relative path of the handoff document written for a HALT; empty otherwise
 */
public record Escalation(int level, String detector, int attempts, String guidance, String handoff) {

    public Escalation {
        Objects.requireNonNull(detector, "detector");
        Objects.requireNonNull(guidance, "guidance");
        Objects.requireNonNull(handoff, "handoff");
        if (level < 1 || level > 3) {
            throw new IllegalArgumentException("Escalation level must be 1-3: " + level);
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must be >= 0");
        }
    }

    public Escalation(int level, String detector, int attempts, String guidance) {
        this(level, detector, attempts, guidance, "");
    }

    public Escalation(int level, String detector, int attempts) {
        this(level, detector, attempts, "", "");
    }

    public Escalation withHandoff(String path, String guidanceSuffix) {
        return new Escalation(level, detector, attempts, guidance + guidanceSuffix, path);
    }
}
