package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.change.ChangeTracker;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.loop.LoopGuard;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.HookEvent;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.state.JavaGuardPaths;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST_EDIT gate (specification &sect;10.2): records the edit in the session ledger,
 * then runs the fast pipeline on every changed Java file against its session baseline,
 * so only problems introduced by this session are reported.
 *
 * <p>Fail-open at every seam: if the ledger cannot be updated (lock timeout, unreadable
 * file) the gate passes with a note rather than comparing against a missing baseline,
 * which would blame legacy code on the agent.
 */
public final class PostEditGate {

    private static final Logger LOG = Logger.getLogger(PostEditGate.class.getName());

    private final JavaGuardConfig config;
    private final ChangeTracker tracker;
    private final FastGatePipeline pipeline;
    private final WorkspaceChanges workspace;
    private final EndOfTurnState.Store endOfTurnState;
    private final JavaGuardPaths paths;
    private final LoopGuard loopGuard;

    public PostEditGate(JavaGuardPaths paths, JavaGuardConfig config, ChangeTracker tracker, Clock clock) {
        this(paths, config, tracker, clock, WorkspaceChanges.none());
    }

    /**
     * @param workspace used once per session, at its first edit, to remember which files were already
     *                  uncommitted so the end-of-turn gate does not blame the agent for them
     */
    public PostEditGate(JavaGuardPaths paths, JavaGuardConfig config, ChangeTracker tracker, Clock clock,
                        WorkspaceChanges workspace) {
        this.paths = paths;
        this.config = config;
        this.tracker = tracker;
        this.pipeline = new FastGatePipeline(paths, config, clock);
        this.workspace = workspace;
        this.endOfTurnState = new EndOfTurnState.Store(paths);
        this.loopGuard = LoopGuard.of(paths, config, clock);
    }

    public GateResult run(HookEvent event) {
        List<ChangeTracker.TrackedFile> tracked;
        try {
            tracked = tracker.recordAfterEdit(event);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Change tracking failed for this post-edit run; passing without analysis.", e);
            return noAnalysis("Change tracking failed (see the log); this edit was not analyzed.");
        }
        seedEndOfTurnState(event.sessionId(), tracked);
        if (!config.fastGate().enabled() || tracked.isEmpty()) {
            return noAnalysis(null);
        }

        List<String> notes = new ArrayList<>();
        List<FastGatePipeline.FileUnderCheck> files = new ArrayList<>();
        for (ChangeTracker.TrackedFile file : tracked) {
            Optional<ChangeTracker.Baseline> baseline = readBaseline(event.sessionId(), file.relativePath());
            if (baseline.isEmpty()) {
                notes.add("No baseline for " + file.relativePath() + "; it was not analyzed.");
                continue;
            }
            files.add(new FastGatePipeline.FileUnderCheck(file.absolutePath(), file.relativePath(),
                    baseline.get().text()));
        }
        Map<String, Path> looked = new LinkedHashMap<>();
        tracked.forEach(t -> looked.put(t.relativePath(), t.absolutePath()));
        LoopGuard.Input loopInput = new LoopGuard.Input(event.sessionId(), GateKind.POST_EDIT, looked, false,
                event.agent().cliName(), event.transcriptPath());
        return pipeline.run(GateKind.POST_EDIT, files, notes, result -> loopGuard.apply(result, loopInput));
    }

    /**
     * First edit of the session: record the uncommitted files that were already there (specification
     * section 10.3 step 1). Whatever the agent edits later through other channels then differs from this
     * snapshot; work in progress the agent never touched does not.
     */
    private void seedEndOfTurnState(String sessionId, List<ChangeTracker.TrackedFile> tracked) {
        try {
            if (endOfTurnState.load(sessionId).isPresent()) {
                return;
            }
            Set<String> ledgerFiles = new HashSet<>();
            tracked.forEach(t -> ledgerFiles.add(t.relativePath()));
            tracker.ledger(sessionId).files().keySet().forEach(ledgerFiles::add);
            Map<String, String> known = new LinkedHashMap<>();
            workspace.dirtyFiles().ifPresent(dirty -> {
                for (Path file : dirty) {
                    Path absolute = file.toAbsolutePath().normalize();
                    if (!absolute.startsWith(paths.projectRoot())) {
                        continue;
                    }
                    String rel = paths.projectRoot().relativize(absolute).toString().replace('\\', '/');
                    if (!ledgerFiles.contains(rel)) {
                        known.put(rel, EndOfTurnState.hashOf(absolute));
                    }
                }
            });
            endOfTurnState.saveIfAbsent(sessionId, EndOfTurnState.seeded(known));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not seed the end-of-turn state; continuing.", e);
        }
    }

    private Optional<ChangeTracker.Baseline> readBaseline(String sessionId, String relativePath) {
        try {
            return tracker.baseline(sessionId, relativePath);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not read the baseline of " + relativePath, e);
            return Optional.empty();
        }
    }

    private static GateResult noAnalysis(String note) {
        return new GateResult(GateKind.POST_EDIT, Verdict.PASS, List.of(), List.of(),
                note == null ? List.of() : List.of(note), Optional.empty(), Optional.empty(), List.of(),
                Duration.ZERO);
    }
}
