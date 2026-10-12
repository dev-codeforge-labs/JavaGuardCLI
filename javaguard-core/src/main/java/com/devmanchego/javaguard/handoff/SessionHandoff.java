package com.devmanchego.javaguard.handoff;

import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.loop.BlockedFindings;
import com.devmanchego.javaguard.loop.LoopGuard;
import com.devmanchego.javaguard.loop.LoopGuard.HistoryLine;
import com.devmanchego.javaguard.state.JavaGuardPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The handoff of a session on demand ({@code javaguard handoff}): assembled from what the loop guard persisted - the
 * latest blocked run's findings ({@code blocking.json}), the history, the snapshots and the session info - without
 * re-running any gate.
 */
public final class SessionHandoff {

    /** Why there is no handoff, or where it is. */
    public record Outcome(Optional<String> path, String message) {
    }

    private final JavaGuardPaths paths;
    private final JavaGuardConfig config;
    private final Clock clock;

    public SessionHandoff(JavaGuardPaths paths, JavaGuardConfig config, Clock clock) {
        this.paths = paths;
        this.config = config;
        this.clock = clock;
    }

    /** The most recently active session, or empty when the project has none. */
    public Optional<String> latestSession() {
        if (!Files.isDirectory(paths.sessionsDir())) {
            return Optional.empty();
        }
        try (Stream<Path> dirs = Files.list(paths.sessionsDir())) {
            return dirs.filter(Files::isDirectory).filter(d -> Files.isRegularFile(d.resolve("history.jsonl")))
                    .max((a, b) -> Long.compare(modified(a.resolve("history.jsonl")), modified(b.resolve("history.jsonl"))))
                    .map(d -> d.getFileName().toString());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Outcome generate(String sessionId) {
        Path sessionDir = paths.sessionDir(sessionId);
        List<HistoryLine> history;
        try {
            history = LoopGuard.readHistory(sessionDir);
        } catch (IOException e) {
            return new Outcome(Optional.empty(), "Could not read the history of session " + sessionId + ": " + e.getMessage());
        }
        if (history.isEmpty()) {
            return new Outcome(Optional.empty(), "Session " + sessionId + " has no recorded gate runs.");
        }
        Optional<BlockedFindings.Snapshot> blocked = BlockedFindings.read(sessionDir);
        String gate = blocked.map(BlockedFindings.Snapshot::gate).orElse(history.get(history.size() - 1).gate());
        int attempts = 0;
        String detector = "";
        Map<String, Path> files = new LinkedHashMap<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            HistoryLine line = history.get(i);
            if (!line.gate().equals(gate)) {
                continue;
            }
            if (!"BLOCK".equals(line.verdict()) && !"HALT".equals(line.verdict())) {
                break;
            }
            attempts++;
            if (detector.isEmpty()) {
                detector = line.detector();
            }
            if (files.isEmpty() && line.fileHashes() != null) {
                line.fileHashes().keySet().forEach(rel -> files.put(rel, paths.projectRoot().resolve(rel)));
            }
        }
        LoopGuard.SessionInfo info = LoopGuard.sessionInfo(sessionDir).orElse(new LoopGuard.SessionInfo("", "", ""));
        Optional<Path> transcript = info.transcript().isEmpty() ? Optional.empty() : Optional.of(Path.of(info.transcript()));
        Optional<String> written = new HandoffService(paths, config, clock).write(new HandoffBuilder.Request(info.agent(), sessionId,
                gate, Math.max(attempts, 1), detector, blocked.map(BlockedFindings.Snapshot::findings).orElse(List.of()), files,
                history, sessionDir, paths.projectRoot(), transcript));
        return written.map(p -> new Outcome(Optional.of(p), "Handoff written to " + p))
                .orElseGet(() -> new Outcome(Optional.empty(), "The handoff could not be written (see the log)."));
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
