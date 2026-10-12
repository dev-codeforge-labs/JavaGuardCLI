package com.devmanchego.javaguard.loop;

import com.devmanchego.javaguard.loop.EscalationMachine.Action;
import com.devmanchego.javaguard.loop.EscalationMachine.Decision;
import com.devmanchego.javaguard.model.GateKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 12 TDD, written before the machine: the escalation ladder of specification &sect;13.3 as a table of run
 * sequences. Each row feeds runs to {@link EscalationMachine#step} one by one, exactly as the gates will, and states the
 * level and action expected after every run.
 *
 * <p>Notation: {@code b("a b", 2, "F=1")} is a blocked run whose blocking fingerprints are {@code a} and {@code b}, with
 * 2 blocking findings, after which file F has content hash 1; {@code pass("F=1")} is a run with nothing blocking.
 */
class EscalationMachineTest {

    private static final LoopSettings DEFAULTS = new LoopSettings(3, 6, 5, 12, 3);

    /** One run of a scripted sequence and what must happen after it. */
    private record Step(GateKind gate, boolean blocked, Set<String> fingerprints, int count, Map<String, String> files,
                        boolean stopHookActive, int expectedLevel, Action expectedAction) {
    }

    private static Map<String, String> files(String spec) {
        Map<String, String> files = new LinkedHashMap<>();
        if (!spec.isBlank()) {
            for (String pair : spec.split(" ")) {
                String[] kv = pair.split("=");
                files.put(kv[0], kv[1]);
            }
        }
        return files;
    }

    private static Set<String> fps(String spec) {
        return Set.of(spec.split(" "));
    }

    private static Step b(String fingerprints, int count, String files, int level, Action action) {
        return new Step(GateKind.END_OF_TURN, true, fps(fingerprints), count, files(files), false, level, action);
    }

    private static Step pe(String fingerprints, int count, String files, int level, Action action) {
        return new Step(GateKind.POST_EDIT, true, fps(fingerprints), count, files(files), false, level, action);
    }

    private static Step pass(String files) {
        return new Step(GateKind.END_OF_TURN, false, Set.of(), 0, files(files), false, 0, Action.NONE);
    }

    private static Step pePass(String files) {
        return new Step(GateKind.POST_EDIT, false, Set.of(), 0, files(files), false, 0, Action.NONE);
    }

    private static Step stopHookActive(String fingerprints, int count, String files, int level, Action action) {
        return new Step(GateKind.END_OF_TURN, true, fps(fingerprints), count, files(files), true, level, action);
    }

    /** Feeds the steps through the machine like the gates do, checking each decision. Returns the decisions. */
    private static List<Decision> play(LoopSettings settings, boolean consultation, Step... steps) {
        Map<GateKind, Episode> episodes = new LinkedHashMap<>();
        List<RunRecord> history = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>();
        long run = 0;
        for (Step step : steps) {
            run++;
            RunRecord record = new RunRecord(run, step.gate(), step.blocked(), List.copyOf(step.fingerprints()), step.count(),
                    step.files(), step.stopHookActive());
            List<RunRecord> earlier = history.stream().filter(r -> r.gate() == step.gate()).toList();
            Decision decision = EscalationMachine.step(episodes.getOrDefault(step.gate(), Episode.none()), earlier, record,
                    settings, consultation);
            episodes.put(step.gate(), decision.next());
            history.add(record);
            decisions.add(decision);
            assertThat(decision.level()).as("level after run %d", run).isEqualTo(step.expectedLevel());
            assertThat(decision.action()).as("action after run %d", run).isEqualTo(step.expectedAction());
        }
        return decisions;
    }

    private static List<Decision> play(Step... steps) {
        return play(DEFAULTS, false, steps);
    }

    // --- the detectors ------------------------------------------------------------------------------------

    @Test
    void repeatTheSameBlockingSignatureThreeTimesWhileChangingTheCodeReachesLevelOne() {
        List<Decision> d = play(
                b("x", 1, "F=1", 0, Action.NONE),
                b("x", 1, "F=2", 0, Action.NONE),
                b("x", 1, "F=3", 1, Action.HINT));

        assertThat(d.get(2).detector()).contains("repeat");
        assertThat(d.get(2).attempts()).isEqualTo(3);
    }

    @Test
    void theSameSignatureWithoutAnyCodeChangeIsNotTheAgentTryingSoRepeatDoesNotFire() {
        play(pe("x", 1, "F=1", 0, Action.NONE),
                pe("x", 1, "F=1", 0, Action.NONE),
                pe("x", 1, "F=1", 0, Action.NONE));
    }

    @Test
    void returningAFileToAContentAlreadySeenInABlockedRunIsOscillation() {
        List<Decision> d = play(
                b("x y", 2, "F=1", 0, Action.NONE),
                b("x z", 2, "F=2", 0, Action.NONE),
                b("x y", 2, "F=1", 1, Action.HINT));

        assertThat(d.get(2).detector()).contains("oscillation");
    }

    @Test
    void aFileThatDidNotChangeInThisRunIsNotOscillation() {
        play(pe("x y", 2, "F=1 G=1", 0, Action.NONE),
                pe("x z", 2, "F=1 G=2", 0, Action.NONE),
                pe("x y", 2, "F=1 G=3", 0, Action.NONE));
    }

    @Test
    void aSteadilyDecreasingBlockingCountNeverEscalates() {
        play(b("a b c d e f", 6, "F=1", 0, Action.NONE),
                b("a b c d e", 5, "F=2", 0, Action.NONE),
                b("a b c d", 4, "F=3", 0, Action.NONE),
                b("a b c", 3, "F=4", 0, Action.NONE),
                b("a b", 2, "F=5", 0, Action.NONE),
                b("a", 1, "F=6", 0, Action.NONE));
    }

    @Test
    void aDecreaseWithAnOccasionalMissIsStillProgress() {
        play(b("a b c d e", 5, "F=1", 0, Action.NONE),
                b("a b c d f", 5, "F=2", 0, Action.NONE),
                b("a b c d", 4, "F=3", 0, Action.NONE),
                b("a b c g", 4, "F=4", 0, Action.NONE),
                b("a b c", 3, "F=5", 0, Action.NONE));
    }

    @Test
    void noNetDecreaseOverFiveBlockedRunsIsNoProgress() {
        List<Decision> d = play(
                pe("a b c", 3, "F=1", 0, Action.NONE),
                pe("a b d", 3, "F=2", 0, Action.NONE),
                pe("a b c e", 4, "F=3", 0, Action.NONE),
                pe("a b d", 3, "F=4", 0, Action.NONE),
                pe("a b e", 3, "F=5", 1, Action.HINT));

        assertThat(d.get(4).detector()).contains("no-progress");
    }

    @Test
    void theStopGuardFiresAfterThreeEndOfTurnBlocksWithoutReductionEvenWithoutCodeChanges() {
        List<Decision> d = play(
                b("x", 2, "F=1", 0, Action.NONE),
                b("x", 2, "F=1", 0, Action.NONE),
                b("x", 2, "F=1", 1, Action.HINT));

        assertThat(d.get(2).detector()).contains("stop-guard");
    }

    @Test
    void theStopGuardIsAnEndOfTurnDetectorOnly() {
        play(pe("x", 2, "F=1", 0, Action.NONE),
                pe("x", 2, "F=1", 0, Action.NONE),
                pe("x", 2, "F=1", 0, Action.NONE));
    }

    @Test
    void stopHookActiveWithAnUnchangedSignatureFiresTheStopGuardAtOnce() {
        List<Decision> d = play(
                b("x", 1, "F=1", 0, Action.NONE),
                stopHookActive("x", 1, "F=2", 1, Action.HINT));

        assertThat(d.get(1).detector()).contains("stop-guard");
    }

    @Test
    void stopHookActiveWithADifferentSignatureIsNotAStall() {
        play(b("x y", 2, "F=1", 0, Action.NONE),
                stopHookActive("x", 1, "F=2", 0, Action.NONE));
    }

    // --- the ladder ---------------------------------------------------------------------------------------

    @Test
    void withoutConsultationTheSecondFiringHaltsAndEachFiringNeedsAFreshWindow() {
        List<Decision> d = play(
                b("x", 1, "F=1", 0, Action.NONE),
                b("x", 1, "F=2", 0, Action.NONE),
                b("x", 1, "F=3", 1, Action.HINT),
                b("x", 1, "F=4", 1, Action.HINT),     // the window restarted after the escalation
                b("x", 1, "F=5", 1, Action.HINT),
                b("x", 1, "F=6", 3, Action.HALT),     // fires again; level 2 needs a consultation, so straight to 3
                b("x", 1, "F=7", 0, Action.NONE));    // after a halt the next run starts a fresh episode

        assertThat(d.get(5).detector()).contains("repeat");
        assertThat(d.get(5).attempts()).isEqualTo(6);
    }

    @Test
    void withConsultationLevelTwoAsksForASecondOpinionAndTheNextFiringHalts() {
        play(DEFAULTS, true,
                b("x", 1, "F=1", 0, Action.NONE),
                b("x", 1, "F=2", 0, Action.NONE),
                b("x", 1, "F=3", 1, Action.HINT),
                b("x", 1, "F=4", 1, Action.HINT),
                b("x", 1, "F=5", 1, Action.HINT),
                b("x", 1, "F=6", 2, Action.CONSULT),
                b("x", 1, "F=7", 2, Action.CONSULT),
                b("x", 1, "F=8", 2, Action.CONSULT),
                b("x", 1, "F=9", 3, Action.HALT));
    }

    @Test
    void aFailedConsultationHaltsOnTheNextBlockedRun() {
        Episode atTwo = new Episode(2, Set.of("x"), 6, 1, 6, "repeat", Episode.Consultation.FAILED);
        RunRecord next = new RunRecord(7, GateKind.END_OF_TURN, true, List.of("x"), 1, Map.of("F", "7"), false);

        Decision d = EscalationMachine.step(atTwo, List.of(), next, DEFAULTS, true);

        assertThat(d.level()).isEqualTo(3);
        assertThat(d.action()).isEqualTo(Action.HALT);
        assertThat(d.detector()).contains("consultation-failed");
    }

    @Test
    void maxBlockedRunsHaltsAnEpisodeThatNoDetectorCatches() {
        List<Step> steps = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            StringBuilder set = new StringBuilder("core");
            for (int j = 0; j < 20 - i; j++) {
                set.append(" e").append(j);
            }
            steps.add(b(set.toString(), 21 - i, "F=" + i, i == 12 ? 3 : 0, i == 12 ? Action.HALT : Action.NONE));
        }

        List<Decision> d = play(steps.toArray(Step[]::new));

        assertThat(d.get(11).detector()).contains("max-blocked-runs");
    }

    // --- what resets and what does not ---------------------------------------------------------------------

    @Test
    void aBlockingSetDisjointFromThePreviousOneIsANewProblemAndStartsAtZero() {
        play(b("x", 1, "F=1", 0, Action.NONE),
                b("x", 1, "F=2", 0, Action.NONE),
                b("x", 1, "F=3", 1, Action.HINT),
                b("y", 1, "F=4", 0, Action.NONE),
                b("y", 1, "F=5", 0, Action.NONE),
                b("y", 1, "F=6", 1, Action.HINT));
    }

    @Test
    void aBlockingSetThatStillSharesAProblemKeepsTheLevel() {
        play(b("x y", 2, "F=1", 0, Action.NONE),
                b("x y", 2, "F=2", 0, Action.NONE),
                b("x y", 2, "F=3", 1, Action.HINT),
                b("x", 1, "F=4", 1, Action.HINT),     // y fixed, x still there: levels never decrease
                b("x z", 2, "F=5", 1, Action.HINT));
    }

    @Test
    void aRunWithNothingBlockingEndsTheEpisode() {
        play(b("x", 1, "F=1", 0, Action.NONE),
                b("x", 1, "F=2", 0, Action.NONE),
                b("x", 1, "F=3", 1, Action.HINT),
                pass("F=4"),
                b("x", 1, "F=5", 0, Action.NONE),
                b("x", 1, "F=6", 0, Action.NONE),
                b("x", 1, "F=7", 1, Action.HINT));
    }

    @Test
    void gateKindsHaveTheirOwnEpisodesSoInterleavedEditsDoNotBreakAnEndOfTurnStreak() {
        play(b("x", 1, "F=1", 0, Action.NONE),
                pePass("F=2"),
                pe("q", 1, "F=3", 0, Action.NONE),
                b("x", 1, "F=4", 0, Action.NONE),
                pePass("F=5"),
                b("x", 1, "F=6", 1, Action.HINT));
    }

    @Test
    void aConsumedSolutionResetsTheLevelToOneWithAFreshWindow() {
        Episode atTwo = new Episode(2, Set.of("x"), 7, 1, 6, "repeat", Episode.Consultation.REQUESTED);

        Episode afterSolution = EscalationMachine.solutionConsumed(atTwo, 8);

        assertThat(afterSolution.level()).isEqualTo(1);
        assertThat(afterSolution.lastEscalationRun()).isEqualTo(8);
        assertThat(afterSolution.consultation()).isEqualTo(Episode.Consultation.NONE);

        List<RunRecord> earlier = new ArrayList<>();
        Episode episode = afterSolution;
        for (long run = 9; run <= 11; run++) {
            RunRecord record = new RunRecord(run, GateKind.END_OF_TURN, true, List.of("x"), 1, Map.of("F", String.valueOf(run)), false);
            Decision d = EscalationMachine.step(episode, earlier, record, DEFAULTS, true);
            episode = d.next();
            earlier.add(record);
            assertThat(d.level()).as("run %d", run).isEqualTo(run < 11 ? 1 : 2);
        }
    }

    @Test
    void theSettingsComeFromTheConfiguration() {
        LoopSettings two = new LoopSettings(2, 6, 5, 12, 3);

        play(two, false,
                b("x", 1, "F=1", 0, Action.NONE),
                b("x", 1, "F=2", 1, Action.HINT));
    }

    @Test
    void theDecisionCarriesTheDetectorOfTheLastEscalationWhileTheLevelHolds() {
        List<Decision> d = play(
                b("x", 1, "F=1", 0, Action.NONE),
                b("x", 1, "F=2", 0, Action.NONE),
                b("x", 1, "F=3", 1, Action.HINT),
                b("x", 1, "F=4", 1, Action.HINT));

        assertThat(d.get(3).detector()).isEqualTo(Optional.of("repeat"));
        assertThat(d.get(0).detector()).isEmpty();
    }
}
