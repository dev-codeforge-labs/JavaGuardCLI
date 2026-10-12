package com.devmanchego.javaguard.change;

/** Where a file's baseline came from (specification &sect;9.1), most to least exact. */
public enum BaselineSource {
    /** Copied by the PRE_EDIT hook before the first edit. */
    SNAPSHOT,
    /** Recovered by reverse-applying the agent's edit fragments. */
    RECONSTRUCTED,
    /** {@code git show HEAD:<path>}; approximate (pre-session uncommitted changes count as the agent's). */
    GIT,
    /** New file, or nothing better available. */
    EMPTY
}
