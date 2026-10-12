package com.devmanchego.javaguard.model;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@code javaguard:ignore} marker found in source (specification &sect;11.3).
 * Populated in Phase 4; the shape is fixed here because {@link GateResult}
 * carries new suppressions to the user.
 *
 * @param ruleId rule being suppressed
 * @param file   project-relative file
 * @param line   1-based line of the marker
 * @param reason justification given; empty when missing (itself JG-SUP-001)
 */
public record Suppression(String ruleId, Path file, int line, Optional<String> reason) {

    public Suppression {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(reason, "reason");
    }
}
