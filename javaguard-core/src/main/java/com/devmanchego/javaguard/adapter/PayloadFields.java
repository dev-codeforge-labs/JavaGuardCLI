package com.devmanchego.javaguard.adapter;

import com.devmanchego.javaguard.model.HookEvent;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Tolerant accessors for payload fields shared by all agents. A field with
 * the wrong JSON type is treated as absent, never as an error: agents add and
 * reshape fields between versions, and a guard must not break because of it.
 */
public final class PayloadFields {

    private PayloadFields() {
    }

    /** Non-blank textual value of {@code field}. */
    public static Optional<String> text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.asText());
    }

    /** Textual value of {@code field}, possibly empty ("" is kept, not treated as absent). */
    public static Optional<String> rawText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual()) {
            return Optional.empty();
        }
        return Optional.of(value.asText());
    }

    public static boolean bool(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isBoolean() && value.booleanValue();
    }

    /** First non-blank textual value among {@code fields}. */
    public static Optional<String> firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            Optional<String> value = text(node, field);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    public static String sessionId(JsonNode payload) {
        return text(payload, "session_id").orElse(HookEvent.UNKNOWN_SESSION);
    }

    /** Payload {@code cwd}, else the context's fallback. */
    public static Path cwd(JsonNode payload, ParseContext ctx) {
        return text(payload, "cwd")
                .map(raw -> ctx.paths().resolve(raw, ctx.fallbackCwd()))
                .orElse(ctx.fallbackCwd());
    }

    public static Optional<Path> transcriptPath(JsonNode payload, ParseContext ctx, Path cwd) {
        return text(payload, "transcript_path").map(raw -> ctx.paths().resolve(raw, cwd));
    }
}
