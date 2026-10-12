package com.devmanchego.javaguard.adapter.codex;

import com.devmanchego.javaguard.model.ChangeType;
import com.devmanchego.javaguard.model.EditFragment;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One file operation in a Codex {@code apply_patch} envelope, with paths
 * exactly as written in the patch (not yet resolved).
 *
 * @param type      ADD, UPDATE, DELETE, or MOVE (an Update followed by {@code *** Move to:})
 * @param path      path in the file header ({@code Add/Update/Delete File:})
 * @param movedTo   destination for MOVE
 * @param fragments one per hunk for UPDATE/MOVE; empty for ADD and DELETE
 */
public record PatchFileOp(ChangeType type, String path, Optional<String> movedTo, List<EditFragment> fragments) {

    public PatchFileOp {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(movedTo, "movedTo");
        fragments = List.copyOf(Objects.requireNonNull(fragments, "fragments"));
    }
}
