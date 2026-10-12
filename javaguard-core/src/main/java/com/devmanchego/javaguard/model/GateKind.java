package com.devmanchego.javaguard.model;

import java.util.Locale;
import java.util.Optional;

/** The JavaGuard evaluation being run (specification &sect;3, &sect;10). */
public enum GateKind {
    /** Baseline snapshot before an edit; always PASS. */
    PRE_EDIT("pre-edit"),
    /** Fast per-edit gate. */
    POST_EDIT("post-edit"),
    /** Full gate when the agent ends its turn. */
    END_OF_TURN("end-of-turn"),
    /** Records the working tree when a turn begins, so edits made through a shell can be told from earlier work; always PASS. */
    TURN_START("turn-start");

    private final String cliName;

    GateKind(String cliName) {
        this.cliName = cliName;
    }

    public String cliName() {
        return cliName;
    }

    /** Whether the event belongs to a tool call (and so may carry a tool name and file changes). */
    public boolean concernsTools() {
        return this == PRE_EDIT || this == POST_EDIT;
    }

    /** Parses {@code pre-edit|post-edit|end-of-turn}, case-insensitively. */
    public static Optional<GateKind> fromCliName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String normalized = name.strip().toLowerCase(Locale.ROOT);
        for (GateKind kind : values()) {
            if (kind.cliName.equals(normalized)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
