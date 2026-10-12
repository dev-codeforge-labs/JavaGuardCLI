package com.devmanchego.javaguard.model;

/** Gate outcome, least to most severe (specification &sect;6.1, &sect;6.4). */
public enum Verdict {
    PASS,
    ADVISE,
    BLOCK,
    HALT;

    /** The more severe of the two verdicts. */
    public Verdict max(Verdict other) {
        return ordinal() >= other.ordinal() ? this : other;
    }
}
