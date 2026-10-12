package com.devmanchego.javaguard.change;

import com.devmanchego.javaguard.model.ChangeType;
import com.devmanchego.javaguard.model.FileChange;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.HookEvent;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Per-session baselines and the file ledger (specification &sect;9.1, &sect;9.4,
 * &sect;19).
 *
 * <p>Baseline acquisition, first touch wins, most exact source first:
 * pre-edit snapshot, reconstruction from the edit fragments, {@code git show
 * HEAD}, empty. Whatever the source, the baseline bytes are materialized in
 * {@code state/<session>/baseline/<path-hash>}, so later gates never re-derive
 * them.
 *
 * <p>Concurrency: every ledger read-modify-write happens under
 * {@link SessionLock}. After-edit recording is two-phase so that the slow part
 * (running git) never holds the lock: decide which files need a baseline
 * (locked), acquire those baselines (unlocked), then store them only if still
 * absent (locked) &mdash; a concurrent hook that got there first wins.
 */
public final class ChangeTracker {

    private static final Logger LOG = Logger.getLogger(ChangeTracker.class.getName());
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(2);
    /** Tree model only; see {@link LedgerJson} for why the ledger is not data-bound. */
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** A file touched by the current event, with its ledger entry after the update. */
    public record TrackedFile(String relativePath, Path absolutePath, LedgerEntry entry, boolean firstTouch) {
    }

    /** Baseline content and provenance. */
    public record Baseline(byte[] content, BaselineSource source, boolean approximate) {
        public Baseline {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(source, "source");
        }

        public String text() {
            return new String(content, StandardCharsets.UTF_8);
        }
    }

    private final JavaGuardPaths paths;
    private final GitClient git;
    private final Clock clock;

    public ChangeTracker(JavaGuardPaths paths, GitClient git, Clock clock) {
        this.paths = paths;
        this.git = git;
        this.clock = clock;
    }

    // --- PRE_EDIT -----------------------------------------------------------

    /** Snapshots every touched file that has no baseline yet (specification section 9.1 step 1). */
    public void snapshotBeforeEdit(HookEvent event) throws IOException {
        String gateRun = gateRun(GateKind.PRE_EDIT);
        try (SessionLock ignored = lock(event.sessionId())) {
            Ledger ledger = readLedger(event.sessionId());
            for (FileChange change : event.changes()) {
                Optional<String> rel = relativize(change.path());
                if (rel.isEmpty() || ledger.files().containsKey(rel.get())) {
                    continue;
                }
                Path source = change.type() == ChangeType.MOVE ? change.movedFrom().orElse(change.path()) : change.path();
                Optional<byte[]> content = read(source);
                LedgerEntry entry = storeBaseline(event.sessionId(), rel.get(),
                        new Baseline(content.orElse(new byte[0]), BaselineSource.SNAPSHOT, false),
                        content.map(Hashing::sha256).orElse(null), content.isEmpty(), gateRun);
                ledger = ledger.with(rel.get(), entry);
            }
            writeLedger(event.sessionId(), ledger);
        }
    }

    // --- POST_EDIT ----------------------------------------------------------

    /** Ensures every touched file has a baseline, and records the file's current state. */
    public List<TrackedFile> recordAfterEdit(HookEvent event) throws IOException {
        String session = event.sessionId();
        Map<String, FileChange> changes = new LinkedHashMap<>();
        for (FileChange change : event.changes()) {
            relativize(change.path()).ifPresent(rel -> changes.put(rel, change));
        }
        if (changes.isEmpty()) {
            return List.of();
        }

        // Phase A (locked): which files are touched for the first time?
        List<String> needBaseline = new ArrayList<>();
        try (SessionLock ignored = lock(session)) {
            Ledger ledger = readLedger(session);
            changes.keySet().stream().filter(rel -> !ledger.files().containsKey(rel)).forEach(needBaseline::add);
        }

        // Phase B (unlocked): acquire those baselines; may run git.
        Map<String, Baseline> acquired = new LinkedHashMap<>();
        for (String rel : needBaseline) {
            FileChange change = changes.get(rel);
            acquired.put(rel, acquire(change, read(change.path())));
        }

        // Phase C (locked): store baselines still missing, record current state for all.
        String gateRun = gateRun(GateKind.POST_EDIT);
        List<TrackedFile> tracked = new ArrayList<>();
        try (SessionLock ignored = lock(session)) {
            Ledger ledger = readLedger(session);
            for (Map.Entry<String, FileChange> e : changes.entrySet()) {
                String rel = e.getKey();
                Optional<byte[]> current = read(e.getValue().path());
                String hash = current.map(Hashing::sha256).orElse(null);
                LedgerEntry existing = ledger.files().get(rel);
                LedgerEntry entry;
                boolean firstTouch = existing == null;
                if (firstTouch) {
                    Baseline baseline = acquired.containsKey(rel)
                            ? acquired.get(rel)
                            : acquire(e.getValue(), current); // appeared between phases; rare
                    entry = storeBaseline(session, rel, baseline, hash, current.isEmpty(), gateRun);
                } else {
                    entry = existing.seen(hash, current.isEmpty(), gateRun);
                }
                ledger = ledger.with(rel, entry);
                tracked.add(new TrackedFile(rel, e.getValue().path(), entry, firstTouch));
            }
            writeLedger(session, ledger);
        }
        return tracked;
    }

    /** Baseline acquisition steps 2-4 of specification section 9.1 (step 1 is {@link #snapshotBeforeEdit}). */
    Baseline acquire(FileChange change, Optional<byte[]> current) {
        if (change.type() == ChangeType.ADD) {
            return new Baseline(new byte[0], BaselineSource.EMPTY, false);
        }
        boolean editable = change.type() == ChangeType.UPDATE || change.type() == ChangeType.MOVE;
        if (editable && !change.fragments().isEmpty() && current.isPresent()) {
            Optional<String> original = BaselineReconstructor.reconstruct(
                    new String(current.get(), StandardCharsets.UTF_8), change.fragments());
            if (original.isPresent()) {
                return new Baseline(original.get().getBytes(StandardCharsets.UTF_8), BaselineSource.RECONSTRUCTED, false);
            }
        }
        Path gitPath = change.type() == ChangeType.MOVE ? change.movedFrom().orElse(change.path()) : change.path();
        Optional<byte[]> head = git.showHead(gitPath);
        if (head.isPresent()) {
            return new Baseline(head.get(), BaselineSource.GIT, true);
        }
        return new Baseline(new byte[0], BaselineSource.EMPTY, false);
    }

    // --- queries and maintenance --------------------------------------------

    public Optional<Baseline> baseline(String sessionId, String relativePath) throws IOException {
        LedgerEntry entry;
        try (SessionLock ignored = lock(sessionId)) {
            entry = readLedger(sessionId).files().get(relativePath);
        }
        if (entry == null) {
            return Optional.empty();
        }
        Path file = baselineDir(sessionId).resolve(entry.baselineFile());
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(new Baseline(Files.readAllBytes(file), entry.baselineSource(), entry.approximate()));
    }

    public Ledger ledger(String sessionId) throws IOException {
        try (SessionLock ignored = lock(sessionId)) {
            return readLedger(sessionId);
        }
    }

    /** {@code javaguard baseline reset}: the next gate treats current contents as new first touches. */
    public void resetBaselines(String sessionId) throws IOException {
        try (SessionLock ignored = lock(sessionId)) {
            Files.deleteIfExists(ledgerFile(sessionId));
            deleteRecursively(baselineDir(sessionId));
        }
    }

    /** Session ids with state on disk. */
    public List<String> sessions() throws IOException {
        if (!Files.isDirectory(paths.sessionsDir())) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(paths.sessionsDir())) {
            return dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** Project-relative path with {@code '/'}; empty for files outside the project (specification section 22). */
    public Optional<String> relativize(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path root = paths.projectRoot();
        if (!absolute.startsWith(root) || absolute.equals(root)) {
            return Optional.empty();
        }
        return Optional.of(root.relativize(absolute).toString().replace('\\', '/'));
    }

    // --- internals ------------------------------------------------------------

    private LedgerEntry storeBaseline(String sessionId, String rel, Baseline baseline, String currentHash,
                                      boolean deleted, String gateRun) throws IOException {
        String fileName = Hashing.shortSha256(rel, 16);
        AtomicFiles.write(baselineDir(sessionId).resolve(fileName), baseline.content());
        return new LedgerEntry(clock.instant().toString(), baseline.source(), baseline.approximate(),
                Hashing.sha256(baseline.content()), fileName, currentHash, deleted, List.of(gateRun));
    }

    private Ledger readLedger(String sessionId) {
        Path file = ledgerFile(sessionId);
        if (!Files.isRegularFile(file)) {
            return Ledger.empty(sessionId);
        }
        try {
            Ledger ledger = LedgerJson.read(JSON.readTree(file.toFile()), sessionId);
            return ledger.version() == Ledger.CURRENT_VERSION ? ledger : Ledger.empty(sessionId);
        } catch (IOException | RuntimeException e) {
            // Fail open: a corrupt ledger loses this session's baselines, never the agent's turn.
            LOG.log(Level.WARNING, "Unreadable ledger " + file + "; starting a new one.", e);
            return Ledger.empty(sessionId);
        }
    }

    private void writeLedger(String sessionId, Ledger ledger) throws IOException {
        AtomicFiles.write(ledgerFile(sessionId), JSON.writeValueAsBytes(LedgerJson.write(ledger)));
    }

    private SessionLock lock(String sessionId) throws IOException {
        return SessionLock.acquire(paths.sessionDir(sessionId).resolve(".lock"), LOCK_TIMEOUT);
    }

    private Path ledgerFile(String sessionId) {
        return paths.sessionDir(sessionId).resolve("ledger.json");
    }

    private Path baselineDir(String sessionId) {
        return paths.sessionDir(sessionId).resolve("baseline");
    }

    private String gateRun(GateKind gate) {
        return clock.instant() + " " + gate.cliName();
    }

    private static Optional<byte[]> read(Path file) throws IOException {
        return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
