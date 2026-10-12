package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.install.ClaudeHookConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Reads and writes a Claude Code {@code settings.json} (contracts &sect;3.4). Never replaces a file
 * it cannot parse, and backs up the existing file before changing it.
 */
final class ClaudeSettingsFile {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
            .withZone(ZoneOffset.UTC);

    /** The settings file is present but is not a JSON object; nothing was changed. */
    static final class UnreadableException extends Exception {
        UnreadableException(String message) {
            super(message);
        }
    }

    private final Path file;

    ClaudeSettingsFile(Path file) {
        this.file = file;
    }

    static ClaudeSettingsFile projectScope(Path projectRoot) {
        return new ClaudeSettingsFile(projectRoot.resolve(".claude").resolve("settings.json"));
    }

    static ClaudeSettingsFile userScope(Path userHome) {
        return new ClaudeSettingsFile(userHome.resolve(".claude").resolve("settings.json"));
    }

    Path path() {
        return file;
    }

    boolean exists() {
        return Files.isRegularFile(file);
    }

    /** The current document; an absent or blank file is an empty document. */
    ObjectNode read() throws UnreadableException, IOException {
        if (!exists()) {
            return JSON.createObjectNode();
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            JsonNode node = JSON.readTree(text);
            if (node instanceof ObjectNode object) {
                return object;
            }
            throw new UnreadableException(file + " does not contain a JSON object; not modifying it.");
        } catch (JsonProcessingException e) {
            throw new UnreadableException(file + " is not valid JSON (" + e.getOriginalMessage()
                    + "); not modifying it.");
        }
    }

    /**
     * Writes {@code settings}, first copying the existing file to
     * {@code settings.json.javaguard-backup-<timestamp>}. Returns the backup path when one was made.
     */
    Optional<Path> write(ObjectNode settings, Instant now) throws IOException {
        Optional<Path> backup = Optional.empty();
        if (exists()) {
            Path target = file.resolveSibling(file.getFileName() + ".javaguard-backup-" + STAMP.format(now));
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            backup = Optional.of(target);
        }
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "settings", ".tmp");
        try {
            Files.writeString(temp, ClaudeHookConfig.render(settings), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return backup;
    }
}
