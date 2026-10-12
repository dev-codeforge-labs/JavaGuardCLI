package com.devmanchego.javaguard.gate;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * The files the working tree currently differs from {@code HEAD} in (modified, added, deleted,
 * untracked): how the end-of-turn gate notices edits made outside the agent's edit tools, such as a
 * shell {@code sed -i} (specification &sect;10.3 step 1). A seam so gate tests need no git repository.
 */
@FunctionalInterface
public interface WorkspaceChanges {

    /** Absolute paths under the project root; empty when git cannot say (absent, not a repository). */
    Optional<Set<Path>> dirtyFiles();

    /**
     * Why {@link #dirtyFiles()} was empty when something other than "not a repository" caused it, in words for the user; empty
     * otherwise. Without it a repository git refuses to read looks exactly like a clean one.
     */
    default Optional<String> problem() {
        return Optional.empty();
    }

    static WorkspaceChanges none() {
        return Optional::empty;
    }
}
