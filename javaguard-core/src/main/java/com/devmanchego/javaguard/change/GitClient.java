package com.devmanchego.javaguard.change;

import java.nio.file.Path;
import java.util.Optional;

/** Read-only git access (specification &sect;1.1 rule 2: never a state-changing command). */
public interface GitClient {

    /**
     * Content of {@code file} at {@code HEAD}; empty when git is unavailable,
     * the directory is not a repository, or the file is not tracked.
     */
    Optional<byte[]> showHead(Path file);

    /** A client that never finds anything (no git, or git deliberately unused). */
    static GitClient none() {
        return file -> Optional.empty();
    }
}
