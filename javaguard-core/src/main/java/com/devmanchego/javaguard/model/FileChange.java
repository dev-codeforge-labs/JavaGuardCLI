package com.devmanchego.javaguard.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A file the agent created, modified, moved or deleted (contracts &sect;2).
 *
 * @param path      absolute, normalized path after the change
 * @param type      kind of change
 * @param movedFrom original path, present only when {@code type == MOVE}
 * @param fragments replacements applied, in application order; empty when the
 *                  agent rewrote the whole file ({@code Write}, {@code write_file},
 *                  Codex {@code Add File}) and for DELETE
 */
public record FileChange(Path path, ChangeType type, Optional<Path> movedFrom, List<EditFragment> fragments) {

    public FileChange {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(movedFrom, "movedFrom");
        fragments = List.copyOf(Objects.requireNonNull(fragments, "fragments"));
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("FileChange path must be absolute: " + path);
        }
        if (type == ChangeType.MOVE && movedFrom.isEmpty()) {
            throw new IllegalArgumentException("MOVE requires movedFrom: " + path);
        }
        if (type != ChangeType.MOVE && movedFrom.isPresent()) {
            throw new IllegalArgumentException("movedFrom is only valid for MOVE: " + path);
        }
    }

    /** Whole-file write (or delete): no fragments. */
    public static FileChange rewrite(Path path, ChangeType type) {
        return new FileChange(path, type, Optional.empty(), List.of());
    }

    /** In-place edit described by fragments. */
    public static FileChange edit(Path path, List<EditFragment> fragments) {
        return new FileChange(path, ChangeType.UPDATE, Optional.empty(), fragments);
    }
}
