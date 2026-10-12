package com.devmanchego.javaguard.loop;

import com.devmanchego.javaguard.change.Hashing;
import com.devmanchego.javaguard.change.SessionLock;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.consult.ConsultRunner;
import com.devmanchego.javaguard.consult.ConsultStatus;
import com.devmanchego.javaguard.consult.ConsultSupport;
import com.devmanchego.javaguard.consult.DetachedConsultRunner;
import com.devmanchego.javaguard.handoff.HandoffBuilder;
import com.devmanchego.javaguard.handoff.HandoffService;
import com.devmanchego.javaguard.handoff.SolutionPickup;
import com.devmanchego.javaguard.model.Escalation;
import com.devmanchego.javaguard.notify.Notification;
import com.devmanchego.javaguard.notify.Notifiers;
import com.devmanchego.javaguard.notify.Notifier;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Loop detection around a gate (specification &sect;13): every run of a gate is appended to the session history,
 * the escalation machine decides from it, snapshots of the changed files are kept after each blocked run, and the
 * gate's result gains the escalation (and, at level 3, becomes a HALT).
 *
 * <p>Persisted under {@code .javaguard/state/<session>/}: {@code history.jsonl} (one line per run),
 * {@code escalation.json} (the next run number and each gate kind's episode) and {@code snapshots/<run>/}. Each gate
 * run is a new JVM, so nothing is kept in memory between runs. Every read-modify-write happens under the session
 * lock; if the lock cannot be had in time, or anything fails, the gate's result passes through unchanged (fail-open,
 * &sect;19).
 */
public final class LoopGuard {

    private static final Logger LOG = Logger.getLogger(LoopGuard.class.getName());
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(2);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** What a gate tells the guard about its run. */
    public record Input(String sessionId, GateKind gate, Map<String, Path> files, boolean stopHookActive, String agent,
                        Optional<Path> transcript) {
        public Input {
            files = Map.copyOf(files);
            agent = agent == null ? "" : agent;
        }

        public Input(String sessionId, GateKind gate, Map<String, Path> files, boolean stopHookActive) {
            this(sessionId, gate, files, stopHookActive, "", Optional.empty());
        }
    }

    /** {@code session.json}: what a handoff generated later needs to know about the session. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionInfo(String agent, String transcript, String updated) {
    }

    /** One line of {@code history.jsonl} (specification &sect;13.2, plus the outcome of the escalation). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HistoryLine(long run, String ts, String gate, String verdict, String blockingSignature, int blockingCount,
                              List<String> fingerprints, Map<String, String> fileHashes, String snapshotId,
                              boolean stopHookActive, int level, String detector) {

        RunRecord toRunRecord() {
            boolean blocked = "BLOCK".equals(verdict) || "HALT".equals(verdict);
            return new RunRecord(run, GateKind.fromCliName(gate).orElse(GateKind.POST_EDIT), blocked,
                    fingerprints == null ? List.of() : fingerprints, blockingCount,
                    fileHashes == null ? Map.of() : fileHashes, stopHookActive);
        }
    }

    /** {@code escalation.json}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record State(int version, long nextRun, Map<String, Episode> episodes) {
        public State {
            episodes = episodes == null ? Map.of() : Map.copyOf(episodes);
        }

        static State initial() {
            return new State(1, 1, Map.of());
        }
    }

    private final JavaGuardPaths paths;
    private final LoopSettings settings;
    private final int maxSnapshots;
    private final Clock clock;
    private final boolean consultationAvailable;
    private final Optional<HandoffService> handoff;
    private final Optional<ConsultSupport> consult;
    private final ConsultRunner consultRunner;
    private final Notifier notifier;

    public LoopGuard(JavaGuardPaths paths, LoopSettings settings, int maxSnapshots, Clock clock, boolean consultationAvailable) {
        this(paths, settings, maxSnapshots, clock, consultationAvailable, Optional.empty());
    }

    public LoopGuard(JavaGuardPaths paths, LoopSettings settings, int maxSnapshots, Clock clock, boolean consultationAvailable,
                     Optional<HandoffService> handoff) {
        this(paths, settings, maxSnapshots, clock, consultationAvailable, handoff, Optional.empty(), null);
    }

    public LoopGuard(JavaGuardPaths paths, LoopSettings settings, int maxSnapshots, Clock clock, boolean consultationAvailable,
                     Optional<HandoffService> handoff, Optional<ConsultSupport> consult, ConsultRunner consultRunner) {
        this(paths, settings, maxSnapshots, clock, consultationAvailable, handoff, consult, consultRunner, Notifier.NONE);
    }

    public LoopGuard(JavaGuardPaths paths, LoopSettings settings, int maxSnapshots, Clock clock, boolean consultationAvailable,
                     Optional<HandoffService> handoff, Optional<ConsultSupport> consult, ConsultRunner consultRunner,
                     Notifier notifier) {
        this.notifier = notifier;
        this.paths = paths;
        this.settings = settings;
        this.maxSnapshots = maxSnapshots;
        this.clock = clock;
        this.consultationAvailable = consultationAvailable;
        this.handoff = handoff;
        this.consult = consult;
        this.consultRunner = consultRunner;
    }

    /** The guard as the configuration describes it; second-model consultation only when {@code consult.enabled}. */
    public static LoopGuard of(JavaGuardPaths paths, JavaGuardConfig config, Clock clock) {
        return new LoopGuard(paths, LoopSettings.of(config), config.loop().maxSnapshots(), clock, false,
                Optional.of(new HandoffService(paths, config, clock)),
                config.consult().enabled() ? Optional.of(new ConsultSupport(config.consult(), System.getenv())) : Optional.empty(),
                new DetachedConsultRunner(), Notifiers.forConfig(config.notifications(), paths.projectRoot(), System.getenv()));
    }

    /** Records the run and returns the result with its escalation applied. Never throws. */
    public GateResult apply(GateResult result, Input input) {
        try (SessionLock ignored = SessionLock.acquire(paths.sessionDir(input.sessionId()).resolve(".lock"), LOCK_TIMEOUT)) {
            return applyLocked(result, input);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Loop detection skipped for this " + input.gate().cliName() + " run.", e);
            return result;
        }
    }

    private GateResult applyLocked(GateResult result, Input input) throws IOException {
        Path sessionDir = paths.sessionDir(input.sessionId());
        Files.createDirectories(sessionDir);
        State state = readState(sessionDir);
        List<HistoryLine> history = readHistory(sessionDir);

        long run = state.nextRun();
        boolean blocked = result.verdict() == Verdict.BLOCK;
        List<String> fingerprints = blocked ? result.blocking().stream().map(f -> f.fingerprint().value()).toList() : List.of();
        Map<String, String> hashes = new LinkedHashMap<>();
        input.files().forEach((rel, abs) -> hashes.put(rel, hashOf(abs)));
        RunRecord record = new RunRecord(run, input.gate(), blocked, fingerprints, blocked ? result.blocking().size() : 0,
                hashes, input.stopHookActive());

        List<RunRecord> earlier = history.stream().map(HistoryLine::toRunRecord).filter(r -> r.gate() == input.gate()).toList();
        Episode episode = state.episodes().getOrDefault(input.gate().cliName(), Episode.none());
        Map<String, Episode> episodes = new LinkedHashMap<>(state.episodes());

        // A second opinion waiting for the agent (specification section 14.4). It is delivered where the agent will
        // read it: any post-edit run, and an end-of-turn run that blocks (an end-of-turn advisory goes to the user).
        Optional<SolutionPickup.Solution> solution = input.gate() == GateKind.POST_EDIT || blocked
                ? SolutionPickup.find(paths, input.sessionId()) : Optional.empty();
        if (solution.isPresent()) {
            // The agent gets a fresh chance before a HALT: every episode in progress goes back to level 1.
            episodes.replaceAll((gate, ep) -> ep.active() ? EscalationMachine.solutionConsumed(ep, run) : ep);
            episode = episodes.getOrDefault(input.gate().cliName(), Episode.none());
        }
        if (episode.level() == 2 && episode.consultation() == Episode.Consultation.REQUESTED) {
            episode = reconcileConsultation(sessionDir, episode);
        }
        boolean canConsult = consultationAvailable || consult.flatMap(c -> c.select(input.agent())).isPresent();
        EscalationMachine.Decision decision = EscalationMachine.step(episode, earlier, record, settings, canConsult);

        GateResult outcome = result;
        if (decision.level() >= 1) {
            List<RunRecord> episodeRuns = lastBlocked(earlier, decision.attempts() - 1);
            episodeRuns.add(record);
            String detector = decision.detector().orElse("");
            String guidance = StrategyHints.guidance(decision.level(), detector, decision.attempts(), result.blocking(), episodeRuns);
            Escalation escalation = new Escalation(decision.level(), detector, decision.attempts(), guidance);
            Verdict verdict = decision.action() == EscalationMachine.Action.HALT ? Verdict.HALT : result.verdict();
            outcome = new GateResult(result.gate(), verdict, result.blocking(), result.advisory(), result.notes(),
                    Optional.of(escalation), result.fullReport(), result.newSuppressions(), result.elapsed());
            if (decision.escalated()) {
                LOG.info(() -> input.gate().cliName() + " escalated to level " + decision.level() + " (" + detector + ") after "
                        + decision.attempts() + " attempt(s)");
            }
        }

        if (solution.isPresent()) {
            outcome = new GateResult(outcome.gate(), outcome.verdict(), outcome.blocking(), outcome.advisory(), outcome.notes(),
                    outcome.escalation(), outcome.fullReport(), outcome.newSuppressions(), outcome.elapsed(),
                    Optional.of(solution.get().text()));
        }

        String snapshotId = blocked ? snapshot(sessionDir, run, input.files()) : "";
        appendHistory(sessionDir, new HistoryLine(run, clock.instant().toString(), input.gate().cliName(), outcome.verdict().name(),
                record.signature(), record.blockingCount(), fingerprints, hashes, snapshotId, input.stopHookActive(),
                decision.level(), decision.detector().orElse("")));
        episodes.put(input.gate().cliName(), decision.next());
        writeAtomically(sessionDir.resolve("escalation.json"), PRETTY.writeValueAsBytes(new State(1, run + 1, episodes)));
        writeSessionInfo(sessionDir, input);
        if (blocked) {
            BlockedFindings.write(sessionDir.resolve("blocking.json"), input.gate().cliName(), result.blocking());
        }
        if (solution.isPresent()) {
            SolutionPickup.markConsumed(solution.get()); // After the state is safe: a failure here only repeats the delivery.
        }
        boolean consultNow = decision.action() == EscalationMachine.Action.CONSULT && decision.escalated() && consult.isPresent();
        if ((outcome.verdict() == Verdict.HALT || consultNow) && outcome.escalation().isPresent() && handoff.isPresent()) {
            outcome = withHandoff(outcome, input, sessionDir, decision);
            if (consultNow) {
                startConsultation(outcome, input, sessionDir);
            }
        }
        return outcome;
    }

    /** The episode's consultation as the worker left it: a failure, or a worker that never reported, ends the ladder. */
    private Episode reconcileConsultation(Path sessionDir, Episode episode) {
        Optional<ConsultStatus> status = ConsultStatus.read(sessionDir);
        Duration timeout = consult.map(ConsultSupport::timeout).orElse(Duration.ofMinutes(5));
        if (status.isEmpty() || status.get().state() == ConsultStatus.State.DONE) {
            return episode; // Done: the solution file is picked up above; unknown: wait for the ladder as usual.
        }
        boolean failed = status.get().state() == ConsultStatus.State.FAILED || status.get().abandoned(clock.instant(), timeout);
        if (!failed) {
            return episode;
        }
        LOG.warning(() -> "The second-model consultation did not deliver: " + status.get().message());
        return EscalationMachine.consultationFailed(episode);
    }

    /** Level 2: ask a second model, at most one consultation in flight per session. */
    private void startConsultation(GateResult outcome, Input input, Path sessionDir) {
        try {
            Optional<ConsultStatus> current = ConsultStatus.read(sessionDir);
            Duration timeout = consult.get().timeout();
            if (current.isPresent() && current.get().inFlight(clock.instant(), timeout)) {
                return;
            }
            Optional<ConsultSupport.Plan> plan = consult.get().select(input.agent());
            if (plan.isEmpty() || outcome.escalation().get().handoff().isEmpty()) {
                ConsultStatus.write(sessionDir, ConsultStatus.running(input.agent(), clock.instant())
                        .finished(ConsultStatus.State.FAILED, clock.instant(), -1, "No consultant could be started."));
                return;
            }
            ConsultStatus.write(sessionDir, ConsultStatus.running(plan.get().agent().cliName(), clock.instant()));
            try {
                consultRunner.start(new ConsultRunner.Launch(paths.projectRoot(), input.sessionId(), plan.get().agent()));
                notifier.notify(Notification.consultationStarted(plan.get().agent().cliName(),
                        String.valueOf(paths.projectRoot().getFileName())));
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not start the consultation.", e);
                ConsultStatus.write(sessionDir, ConsultStatus.running(plan.get().agent().cliName(), clock.instant())
                        .finished(ConsultStatus.State.FAILED, clock.instant(), -1, "Could not start: " + e.getMessage()));
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "The consultation was not recorded.", e);
        }
    }

    /** The handoff of a HALT or of a consultation (specification section 14): written after the state, so the history already holds this run. */
    private GateResult withHandoff(GateResult outcome, Input input, Path sessionDir, EscalationMachine.Decision decision) {
        try {
            SessionInfo info = sessionInfo(sessionDir).orElse(new SessionInfo("", "", ""));
            Optional<Path> transcript = input.transcript().or(() -> info.transcript().isEmpty()
                    ? Optional.empty() : Optional.of(Path.of(info.transcript())));
            Optional<String> written = handoff.get().write(new HandoffBuilder.Request(info.agent(), input.sessionId(),
                    input.gate().cliName(), decision.attempts(), decision.detector().orElse(""), outcome.blocking(), input.files(),
                    readHistory(sessionDir), sessionDir, paths.projectRoot(), transcript));
            if (written.isEmpty()) {
                return outcome;
            }
            Escalation escalation = outcome.escalation().get().withHandoff(written.get(), "");
            return new GateResult(outcome.gate(), outcome.verdict(), outcome.blocking(), outcome.advisory(), outcome.notes(),
                    Optional.of(escalation), outcome.fullReport(), outcome.newSuppressions(), outcome.elapsed(), outcome.solution());
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "The handoff could not be produced; the HALT stands without it.", e);
            return outcome;
        }
    }

    private void writeSessionInfo(Path sessionDir, Input input) throws IOException {
        Path file = sessionDir.resolve("session.json");
        SessionInfo previous = sessionInfo(sessionDir).orElse(null);
        String agent = !input.agent().isEmpty() ? input.agent() : previous == null ? "" : previous.agent();
        String transcript = input.transcript().map(Path::toString).orElse(previous == null ? "" : previous.transcript());
        writeAtomically(file, PRETTY.writeValueAsBytes(new SessionInfo(agent, transcript, clock.instant().toString())));
    }

    public static Optional<SessionInfo> sessionInfo(Path sessionDir) {
        Path file = sessionDir.resolve("session.json");
        try {
            return Files.isRegularFile(file) ? Optional.of(JSON.readValue(file.toFile(), SessionInfo.class)) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The last {@code count} blocked runs, oldest first. */
    private static List<RunRecord> lastBlocked(List<RunRecord> runs, int count) {
        List<RunRecord> blocked = runs.stream().filter(RunRecord::blocked).toList();
        return new ArrayList<>(blocked.subList(Math.max(0, blocked.size() - Math.max(0, count)), blocked.size()));
    }

    // --- persistence ------------------------------------------------------------------------------------------

    private static State readState(Path sessionDir) {
        Path file = sessionDir.resolve("escalation.json");
        if (!Files.isRegularFile(file)) {
            return State.initial();
        }
        try {
            State state = JSON.readValue(file.toFile(), State.class);
            return state.nextRun() < 1 ? State.initial() : state;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Unreadable " + file + "; starting a new escalation state.", e);
            return State.initial();
        }
    }

    /** Every readable line; a torn or corrupt line is skipped rather than losing the rest. */
    public static List<HistoryLine> readHistory(Path sessionDir) throws IOException {
        Path file = sessionDir.resolve("history.jsonl");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<HistoryLine> lines = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                lines.add(JSON.readValue(line, HistoryLine.class));
            } catch (IOException | RuntimeException e) {
                LOG.fine(() -> "Skipping an unreadable history line in " + file);
            }
        }
        return lines;
    }

    private static void appendHistory(Path sessionDir, HistoryLine line) throws IOException {
        byte[] bytes = (JSON.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(sessionDir.resolve("history.jsonl"), bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Copies of the run's files under {@code snapshots/<run>/}, for the handoff's attempt diffs; the oldest are pruned. */
    private String snapshot(Path sessionDir, long run, Map<String, Path> files) {
        if (files.isEmpty() || maxSnapshots <= 0) {
            return "";
        }
        String id = String.valueOf(run);
        Path dir = sessionDir.resolve("snapshots").resolve(id);
        try {
            Files.createDirectories(dir);
            Map<String, String> index = new LinkedHashMap<>();
            for (Map.Entry<String, Path> file : files.entrySet()) {
                String name = Hashing.shortSha256(file.getKey(), 16);
                index.put(file.getKey(), Files.isRegularFile(file.getValue()) ? name : "");
                if (Files.isRegularFile(file.getValue())) {
                    Files.copy(file.getValue(), dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.write(dir.resolve("index.json"), JSON.writeValueAsBytes(index));
            prune(sessionDir.resolve("snapshots"));
            return id;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not snapshot run " + run, e);
            return "";
        }
    }

    private void prune(Path snapshots) throws IOException {
        List<Path> dirs;
        try (Stream<Path> list = Files.list(snapshots)) {
            dirs = list.filter(Files::isDirectory).filter(p -> p.getFileName().toString().matches("\\d+"))
                    .sorted(Comparator.comparingLong(p -> Long.parseLong(p.getFileName().toString()))).toList();
        }
        for (int i = 0; i < dirs.size() - maxSnapshots; i++) {
            try (Stream<Path> walk = Files.walk(dirs.get(i))) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static String hashOf(Path file) {
        try {
            return Hashing.sha256(Files.readAllBytes(file));
        } catch (IOException e) {
            return "-";
        }
    }

    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), "escalation", ".tmp");
        try {
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
