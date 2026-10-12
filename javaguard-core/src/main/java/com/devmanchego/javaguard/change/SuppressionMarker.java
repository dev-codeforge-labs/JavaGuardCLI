package com.devmanchego.javaguard.change;

import java.util.Objects;
import java.util.Optional;

/**
 * A {@code javaguard:ignore} marker found in a source file (specification
 * &sect;11.3), with the line range it covers.
 *
 * @param ruleId     rule being suppressed (any id: {@code JG-*}, {@code PMD:*}, ...)
 * @param kind       line comment or {@code @SuppressWarnings} on a declaration
 * @param fromLine   first covered line (1-based)
 * @param toLine     last covered line (1-based, inclusive)
 * @param markerLine where the marker itself is written (reported to the user)
 * @param reason     justification; empty when missing (JG-SUP-001 when new)
 */
public record SuppressionMarker(String ruleId, Kind kind, int fromLine, int toLine, int markerLine,
                                Optional<String> reason) {

    public enum Kind {
        /** {@code // javaguard:ignore <rule> -- <reason>}: its own line and the next. */
        LINE,
        /** {@code @SuppressWarnings("javaguard:<rule>")}: the annotated declaration. */
        ELEMENT
    }

    public SuppressionMarker {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reason, "reason");
    }

    public boolean covers(int line) {
        return line >= 1 && line >= fromLine && line <= toLine;
    }

    /**
     * Identity used to decide whether a marker is new in this session:
     * independent of its line, so moving code around does not make an old
     * marker look new.
     */
    String identity() {
        return ruleId + "|" + kind + "|" + reason.orElse("");
    }
}
