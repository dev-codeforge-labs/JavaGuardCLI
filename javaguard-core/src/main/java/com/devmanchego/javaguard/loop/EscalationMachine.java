package com.devmanchego.javaguard.loop;

import com.devmanchego.javaguard.model.GateKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The escalation ladder of specification &sect;13.3 as a pure function: given the gate kind's episode so far, its
 * earlier runs and the new run, decide the new level and what the gate must do. No I/O, no clock: {@code LoopGuard}
 * persists the inputs and outputs, and {@code EscalationMachineTest} is the specification.
 *
 * <p>Detectors (catalog wording in &sect;13.3), each looking only at the episode's runs <em>after its latest
 * escalation</em>, so that "fires again" means a fresh detection rather than the same streak counted twice:
 * <ul>
 *   <li><b>repeat</b>: the last {@code repeatThreshold} runs have the same blocking signature and the code changed
 *       between each of them (the agent is trying);</li>
 *   <li><b>oscillation</b>: a file changed in this run is back to a content it had in one of the last
 *       {@code oscillationWindow} blocked runs;</li>
 *   <li><b>no-progress</b>: over the last {@code noProgressWindow} blocked runs the blocking count did not go down
 *       (last &ge; first);</li>
 *   <li><b>stop-guard</b> (END_OF_TURN only): the last {@code maxConsecutiveBlocks} runs blocked without the count ever
 *       going down, or the agent reports {@code stop_hook_active} and the signature did not change.</li>
 * </ul>
 * A firing raises the level by one. Level 2 is a second-model consultation; without one (disabled or unavailable)
 * the ladder goes straight to 3. Level 3 is HALT, also reached when the consultation failed or the episode reached
 * {@code maxBlockedRuns}. After a HALT the next run starts a new episode: the user has taken over.
 */
public final class EscalationMachine {

    /** What the gate does with the run. */
    public enum Action {
        /** Nothing to add. */
        NONE,
        /** Add the strategy hint to the report (level 1). */
        HINT,
        /** A second opinion is (being) requested; say so in the report (level 2). */
        CONSULT,
        /** Stop the agent (level 3). */
        HALT
    }

    /**
     * @param next      the episode to persist for the gate kind
     * @param level     the level after this run
     * @param detector  the detector behind the current level, when above 0
     * @param attempts  blocked runs in the episode, this one included
     * @param escalated the level went up in this run
     */
    public record Decision(Episode next, int level, Action action, Optional<String> detector, int attempts, boolean escalated) {
        public Decision {
            Objects.requireNonNull(next, "next");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(detector, "detector");
        }
    }

    static final String REPEAT = "repeat";
    static final String OSCILLATION = "oscillation";
    static final String NO_PROGRESS = "no-progress";
    static final String STOP_GUARD = "stop-guard";
    static final String MAX_BLOCKED_RUNS = "max-blocked-runs";
    static final String CONSULTATION_FAILED = "consultation-failed";

    private EscalationMachine() {
    }

    /**
     * @param episode               the gate kind's current episode ({@link Episode#none()} when there is none)
     * @param earlierRuns           earlier runs of the same gate kind, oldest first
     * @param run                   the run being decided
     * @param consultationAvailable a second-model consultation can be requested
     */
    public static Decision step(Episode episode, List<RunRecord> earlierRuns, RunRecord run, LoopSettings settings,
                                boolean consultationAvailable) {
        if (!run.blocked() || run.fingerprints().isEmpty()) {
            return new Decision(Episode.none(), 0, Action.NONE, Optional.empty(), 0, false);
        }
        Episode current = episode;
        if (!current.active() || Collections.disjoint(current.fingerprints(), run.fingerprints())) {
            current = new Episode(0, run.fingerprints().stream().collect(java.util.stream.Collectors.toSet()), 0, run.run(),
                    run.run() - 1, "", Episode.Consultation.NONE);
        }
        int attempts = current.blockedRuns() + 1;

        if (current.level() == 2 && current.consultation() == Episode.Consultation.FAILED) {
            return halt(attempts, CONSULTATION_FAILED);
        }
        if (attempts >= settings.maxBlockedRuns()) {
            return halt(attempts, MAX_BLOCKED_RUNS);
        }

        List<RunRecord> window = window(current, earlierRuns, run);
        Optional<String> fired = fire(window, run, settings);
        int level = current.level();
        long lastEscalation = current.lastEscalationRun();
        String detector = current.lastDetector();
        Episode.Consultation consultation = current.consultation();
        if (fired.isPresent()) {
            level = Math.min(3, level + 1);
            if (level == 2 && !consultationAvailable) {
                level = 3;
            }
            lastEscalation = run.run();
            detector = fired.get();
            if (level == 2) {
                consultation = Episode.Consultation.REQUESTED;
            }
        }
        if (level >= 3) {
            return halt(attempts, detector);
        }
        Episode next = new Episode(level, java.util.Set.copyOf(run.fingerprints()), attempts, current.startRun(), lastEscalation,
                detector, consultation);
        Action action = switch (level) {
            case 1 -> Action.HINT;
            case 2 -> Action.CONSULT;
            default -> Action.NONE;
        };
        return new Decision(next, level, action, level == 0 ? Optional.empty() : Optional.of(detector), attempts,
                fired.isPresent());
    }

    /** A solution from the second opinion was injected: the agent gets a fresh start at level 1 (specification &sect;14.4). */
    public static Episode solutionConsumed(Episode episode, long run) {
        return new Episode(1, episode.fingerprints(), episode.blockedRuns(), episode.startRun(), run, episode.lastDetector(),
                Episode.Consultation.NONE);
    }

    /** The consultation failed or timed out: the next blocked run halts. */
    public static Episode consultationFailed(Episode episode) {
        return new Episode(episode.level(), episode.fingerprints(), episode.blockedRuns(), episode.startRun(),
                episode.lastEscalationRun(), episode.lastDetector(), Episode.Consultation.FAILED);
    }

    private static Decision halt(int attempts, String detector) {
        return new Decision(Episode.none(), 3, Action.HALT, Optional.of(detector), attempts, true);
    }

    /** The episode's runs after its latest escalation, oldest first, this run last. */
    private static List<RunRecord> window(Episode episode, List<RunRecord> earlierRuns, RunRecord run) {
        List<RunRecord> window = new ArrayList<>();
        for (RunRecord r : earlierRuns) {
            if (r.run() >= episode.startRun() && r.run() > episode.lastEscalationRun() && r.blocked()) {
                window.add(r);
            }
        }
        window.add(run);
        return window;
    }

    private static Optional<String> fire(List<RunRecord> window, RunRecord run, LoopSettings settings) {
        if (repeat(window, settings.repeatThreshold())) {
            return Optional.of(REPEAT);
        }
        if (oscillation(window, settings.oscillationWindow())) {
            return Optional.of(OSCILLATION);
        }
        if (noProgress(window, settings.noProgressWindow())) {
            return Optional.of(NO_PROGRESS);
        }
        if (run.gate() == GateKind.END_OF_TURN && stopGuard(window, settings.maxConsecutiveBlocks())) {
            return Optional.of(STOP_GUARD);
        }
        return Optional.empty();
    }

    private static boolean repeat(List<RunRecord> window, int threshold) {
        if (window.size() < threshold) {
            return false;
        }
        List<RunRecord> last = window.subList(window.size() - threshold, window.size());
        String signature = last.get(last.size() - 1).signature();
        for (int i = 0; i < last.size(); i++) {
            if (!last.get(i).signature().equals(signature)) {
                return false;
            }
            if (i > 0 && last.get(i).fileHashes().equals(last.get(i - 1).fileHashes())) {
                return false; // Nothing changed: the agent is not trying, which is the stop guard's business.
            }
        }
        return true;
    }

    private static boolean oscillation(List<RunRecord> window, int size) {
        if (window.size() < 3) {
            return false; // Going back needs an earlier state, a change away from it, and the return.
        }
        RunRecord current = window.get(window.size() - 1);
        RunRecord previous = window.get(window.size() - 2);
        List<RunRecord> earlier = window.subList(Math.max(0, window.size() - 1 - size), window.size() - 2);
        for (Map.Entry<String, String> file : current.fileHashes().entrySet()) {
            boolean changedNow = !file.getValue().equals(previous.fileHashes().get(file.getKey()));
            if (changedNow && earlier.stream().anyMatch(r -> file.getValue().equals(r.fileHashes().get(file.getKey())))) {
                return true;
            }
        }
        return false;
    }

    private static boolean noProgress(List<RunRecord> window, int size) {
        if (window.size() < size) {
            return false;
        }
        List<RunRecord> last = window.subList(window.size() - size, window.size());
        return last.get(last.size() - 1).blockingCount() >= last.get(0).blockingCount();
    }

    private static boolean stopGuard(List<RunRecord> window, int consecutive) {
        RunRecord current = window.get(window.size() - 1);
        if (current.stopHookActive() && window.size() >= 2
                && window.get(window.size() - 2).signature().equals(current.signature())) {
            return true;
        }
        if (window.size() < consecutive) {
            return false;
        }
        List<RunRecord> last = window.subList(window.size() - consecutive, window.size());
        for (int i = 1; i < last.size(); i++) {
            if (last.get(i).blockingCount() < last.get(i - 1).blockingCount()) {
                return false;
            }
        }
        return true;
    }
}
