package com.devmanchego.javaguard.model;

/** Finding severity, most severe first (specification &sect;6.1). */
public enum Severity {
    BLOCKER,
    CRITICAL,
    MAJOR,
    INFO;

    /** True when this severity is at least as severe as {@code other}. */
    public boolean isAtLeast(Severity other) {
        return ordinal() <= other.ordinal();
    }
}
