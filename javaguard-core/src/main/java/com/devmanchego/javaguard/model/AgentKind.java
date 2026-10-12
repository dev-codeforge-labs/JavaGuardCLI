package com.devmanchego.javaguard.model;

import java.util.Locale;
import java.util.Optional;

/** The AI coding CLI that invoked JavaGuardCLI (contracts &sect;2). */
public enum AgentKind {
    CLAUDE("claude"),
    GEMINI("gemini"),
    CODEX("codex"),
    /** A human running {@code javaguard check}; never an adapter target. */
    MANUAL("manual");

    private final String cliName;

    AgentKind(String cliName) {
        this.cliName = cliName;
    }

    /** Lower-case name used on the command line and in configuration. */
    public String cliName() {
        return cliName;
    }

    /** Parses {@code claude|gemini|codex|manual}, case-insensitively. */
    public static Optional<AgentKind> fromCliName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String normalized = name.strip().toLowerCase(Locale.ROOT);
        for (AgentKind kind : values()) {
            if (kind.cliName.equals(normalized)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
