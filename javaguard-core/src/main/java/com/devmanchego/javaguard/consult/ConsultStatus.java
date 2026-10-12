package com.devmanchego.javaguard.consult;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The consultation of a session ({@code consult.json} in the session directory): one at a time, written by the guard
 * when it starts one and by the detached worker when it ends. A {@code RUNNING} status older than the consultation's
 * timeout (plus a grace period) means the worker died without reporting and counts as a failure.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConsultStatus(State state, String agent, String startedAt, String finishedAt, int exitCode, String message) {

    public enum State { RUNNING, DONE, FAILED }

    private static final Duration GRACE = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static ConsultStatus running(String agent, Instant now) {
        return new ConsultStatus(State.RUNNING, agent, now.toString(), "", -1, "");
    }

    public ConsultStatus finished(State outcome, Instant now, int exit, String note) {
        return new ConsultStatus(outcome, agent, startedAt, now.toString(), exit, note);
    }

    /** Running, and not yet past its deadline. */
    public boolean inFlight(Instant now, Duration timeout) {
        return state == State.RUNNING && !Instant.parse(startedAt).plus(timeout).plus(GRACE).isBefore(now);
    }

    /** Running past its deadline: the worker is gone. */
    public boolean abandoned(Instant now, Duration timeout) {
        return state == State.RUNNING && !inFlight(now, timeout);
    }

    public static Optional<ConsultStatus> read(Path sessionDir) {
        Path file = sessionDir.resolve("consult.json");
        try {
            return Files.isRegularFile(file) ? Optional.of(JSON.readValue(file.toFile(), ConsultStatus.class)) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public static void write(Path sessionDir, ConsultStatus status) throws IOException {
        Files.createDirectories(sessionDir);
        Path target = sessionDir.resolve("consult.json");
        Path temp = Files.createTempFile(sessionDir, "consult", ".tmp");
        try {
            Files.write(temp, JSON.writeValueAsBytes(status));
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
