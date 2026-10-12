package com.devmanchego.javaguard.model;

import java.util.Objects;

/**
 * Line-independent identity of a finding (specification &sect;13.1).
 *
 * <p>Phase 1 fixes only the shape: a lower-case hexadecimal string. How it
 * is computed (message normalization, hashing) is decided in Phase 4 together
 * with baseline scoping, because loop detection (Phase 12) persists
 * fingerprints across runs and cannot tolerate a later change.
 */
public record Fingerprint(String value) {

    public Fingerprint {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9a-f]{1,64}")) {
            throw new IllegalArgumentException("Fingerprint must be 1-64 lower-case hex chars: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
