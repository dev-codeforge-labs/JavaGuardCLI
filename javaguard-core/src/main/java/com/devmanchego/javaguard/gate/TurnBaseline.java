package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.state.JavaGuardPaths;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The working tree as it was when a turn began (specification &sect;10.3, "turn start").
 *
 * <p>The end-of-turn gate finds what a shell command wrote ({@code sed}, {@code cat >}, PowerShell's {@code Set-Content}) by
 * comparing the uncommitted files with the content hashes it recorded. Without a record the first end of turn of a session
 * cannot tell the agent's shell edits from work that was already there, and it ignores both. This records the relevant
 * uncommitted files at the start of every turn, so the comparison always has something to compare with.
 *
 * <p>Only hashes of files that are already uncommitted are stored: a file the agent creates or edits later is not in the
 * record, or differs from it, and so is a change. The other fields of the state (pending files, block counters) are kept.
 */
public final class TurnBaseline {

    private final JavaGuardPaths paths;
    private final WorkspaceChanges workspace;
    private final EndOfTurnState.Store store;

    public TurnBaseline(JavaGuardPaths paths, WorkspaceChanges workspace, EndOfTurnState.Store store) {
        this.paths = paths;
        this.workspace = workspace;
        this.store = store;
    }

    /** Records the relevant uncommitted files of {@code sessionId}; a project without git records nothing to compare. */
    public void record(String sessionId) {
        Path root = paths.projectRoot();
        Map<String, String> current = new LinkedHashMap<>();
        workspace.dirtyFiles().ifPresent(dirty -> {
            for (Path file : dirty) {
                Path absolute = file.toAbsolutePath().normalize();
                if (!absolute.startsWith(root) || absolute.equals(root)) {
                    continue;
                }
                String rel = root.relativize(absolute).toString().replace('\\', '/');
                if (EndOfTurnGate.isRelevant(absolute, rel, Optional.empty())) {
                    current.put(rel, EndOfTurnState.hashOf(absolute));
                }
            }
        });
        Optional<EndOfTurnState> previous = store.load(sessionId);
        if (previous.isEmpty()) {
            store.saveIfAbsent(sessionId, EndOfTurnState.seeded(current));
            return;
        }
        EndOfTurnState state = previous.get();
        Map<String, String> known = new LinkedHashMap<>(state.knownHashes());
        known.putAll(current);
        store.save(sessionId, new EndOfTurnState(state.version(), state.lastRun(), known, state.pendingFiles(),
                state.consecutiveBlocks(), state.lastBlockingCount()));
    }
}
