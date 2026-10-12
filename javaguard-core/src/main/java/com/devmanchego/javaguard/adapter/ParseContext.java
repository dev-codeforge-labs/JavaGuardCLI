package com.devmanchego.javaguard.adapter;

import com.devmanchego.javaguard.model.GateKind;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Inputs an adapter needs besides the payload itself.
 *
 * @param requestedGate gate from {@code --gate}; used only when the payload's
 *                      event name is missing or unknown (the payload wins, because
 *                      the output format must match the real event)
 * @param fallbackCwd   absolute directory used when the payload has no {@code cwd}
 * @param receivedAt    timestamp stamped on the event
 * @param paths         path normalization rules for the current OS
 */
public record ParseContext(Optional<GateKind> requestedGate, Path fallbackCwd, Instant receivedAt, PathNormalizer paths) {

    public ParseContext {
        Objects.requireNonNull(requestedGate, "requestedGate");
        Objects.requireNonNull(fallbackCwd, "fallbackCwd");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(paths, "paths");
        if (!fallbackCwd.isAbsolute()) {
            throw new IllegalArgumentException("fallbackCwd must be absolute: " + fallbackCwd);
        }
    }

    public static ParseContext now(Optional<GateKind> requestedGate, Path fallbackCwd) {
        return new ParseContext(requestedGate, fallbackCwd.toAbsolutePath().normalize(), Instant.now(),
                PathNormalizer.forCurrentOs());
    }
}
