package com.devmanchego.javaguard.consult;

import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.handoff.SolutionPickup;
import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.notify.Notification;
import com.devmanchego.javaguard.notify.Notifier;
import com.devmanchego.javaguard.process.ProcessResult;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.state.JavaGuardPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The body of a consultation (specification &sect;15), run by {@code javaguard consult-run} in a detached process: feed the
 * session's latest handoff to the second model on stdin, wait at most {@code consult.timeoutSeconds}, and on exit 0 with
 * an answer write it as {@code SOLUTION-<session-short>.md}. Whatever happens, {@code consult.json} ends as DONE or
 * FAILED, so the next gate run knows. Consultations are logged with duration and exit code.
 */
public final class ConsultWorker {

    private static final Logger LOG = Logger.getLogger(ConsultWorker.class.getName());

    private final JavaGuardPaths paths;
    private final JavaGuardConfig config;
    private final ProcessRunner processes;
    private final Clock clock;
    private final Map<String, String> env;
    private final java.time.Duration timeout;
    private Notifier notifier = Notifier.NONE;

    public ConsultWorker(JavaGuardPaths paths, JavaGuardConfig config, ProcessRunner processes, Clock clock, Map<String, String> env) {
        this(paths, config, processes, clock, env, java.time.Duration.ofSeconds(config.consult().timeoutSeconds()));
    }

    /** With an explicit timeout instead of {@code consult.timeoutSeconds} (whose minimum is 10 s). */
    public ConsultWorker(JavaGuardPaths paths, JavaGuardConfig config, ProcessRunner processes, Clock clock, Map<String, String> env,
                         java.time.Duration timeout) {
        this.timeout = timeout;
        this.paths = paths;
        this.config = config;
        this.processes = processes;
        this.clock = clock;
        this.env = env;
    }

    /** Tells the user when the consultation ends (only if {@code notify.consultation} is on; the notifier decides). */
    public ConsultWorker notifying(Notifier notifier) {
        this.notifier = notifier;
        return this;
    }

    /** Returns the final status (also persisted). Never throws. */
    public ConsultStatus run(String sessionId, AgentKind agent) {
        Path sessionDir = paths.sessionDir(sessionId);
        ConsultStatus start = ConsultStatus.read(sessionDir).filter(s -> s.state() == ConsultStatus.State.RUNNING)
                .orElse(ConsultStatus.running(agent.cliName(), clock.instant()));
        ConsultStatus end;
        try {
            end = consult(sessionId, agent, start);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "The consultation failed.", e);
            end = start.finished(ConsultStatus.State.FAILED, clock.instant(), -1, "Internal error: " + e.getMessage());
        }
        try {
            ConsultStatus.write(sessionDir, end);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not record the consultation result.", e);
        }
        notifier.notify(Notification.consultationFinished(agent.cliName(), String.valueOf(paths.projectRoot().getFileName()),
                end.state() == ConsultStatus.State.DONE, end.message()));
        return end;
    }

    private ConsultStatus consult(String sessionId, AgentKind agent, ConsultStatus start) throws IOException {
        Optional<Path> handoff = latestHandoff(sessionId);
        if (handoff.isEmpty()) {
            return start.finished(ConsultStatus.State.FAILED, clock.instant(), -1, "There is no handoff to send.");
        }
        Optional<ConsultSupport.Plan> plan = new ConsultSupport(config.consult(), env).planFor(agent);
        if (plan.isEmpty()) {
            return start.finished(ConsultStatus.State.FAILED, clock.instant(), -1, agent.cliName() + " was not found on PATH.");
        }
        ProcessResult result = processes.run(plan.get().command(), paths.projectRoot(), Map.of(),
                timeout, ProcessRunner.DEFAULT_MAX_OUTPUT_CHARS,
                Optional.empty(), Optional.of(Files.readAllBytes(handoff.get())));
        int exit = result.exitCode().orElse(-1);
        LOG.info(() -> "Consultation with " + agent.cliName() + " ended: exit " + (result.timedOut() ? "timeout" : exit) + " in "
                + result.elapsed().toMillis() + " ms");
        if (result.timedOut()) {
            return start.finished(ConsultStatus.State.FAILED, clock.instant(), -1,
                    "Timed out after " + timeout.toSeconds() + " s.");
        }
        if (exit != 0) {
            return start.finished(ConsultStatus.State.FAILED, clock.instant(), exit, "The consultant exited with code " + exit + ".");
        }
        if (result.output().isBlank()) {
            return start.finished(ConsultStatus.State.FAILED, clock.instant(), exit, "The consultant answered nothing.");
        }
        writeSolution(sessionId, result.output());
        return start.finished(ConsultStatus.State.DONE, clock.instant(), exit, "");
    }

    private Optional<Path> latestHandoff(String sessionId) throws IOException {
        if (!Files.isDirectory(paths.handoffDir())) {
            return Optional.empty();
        }
        String prefix = "HANDOFF-" + SolutionPickup.shortId(sessionId) + "-";
        try (Stream<Path> files = Files.list(paths.handoffDir())) {
            return files.filter(p -> p.getFileName().toString().startsWith(prefix)).max(Path::compareTo);
        }
    }

    private void writeSolution(String sessionId, String text) throws IOException {
        Path target = paths.handoffDir().resolve("SOLUTION-" + SolutionPickup.shortId(sessionId) + ".md");
        Path temp = Files.createTempFile(paths.handoffDir(), "solution", ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
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
