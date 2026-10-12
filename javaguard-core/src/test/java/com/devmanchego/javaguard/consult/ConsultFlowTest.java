package com.devmanchego.javaguard.consult;

import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.handoff.HandoffService;
import com.devmanchego.javaguard.loop.LoopGuard;
import com.devmanchego.javaguard.loop.LoopSettings;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doAnswer;

/**
 * Plan Phase 14: level 2 of the ladder end to end with a fake consult CLI. A consultation is requested once; its
 * solution is injected by the next gate run exactly once; a failure, a timeout or a missing consultant leads to HALT.
 */
class ConsultFlowTest {

    private static final String REL = "src/main/java/A.java";
    private static final String SESSION = "sess-c-1";

    /** A clock the test can move. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-05T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    @TempDir
    Path root;

    private final TestClock clock = new TestClock();
    private JavaGuardPaths paths;
    private JavaGuardConfig config;
    private ConsultRunner runner;
    private Path source;
    private final List<com.devmanchego.javaguard.notify.Notification> notified = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        paths = new JavaGuardPaths(root);
        paths.ensureLayout();
        source = root.resolve(REL);
        Files.createDirectories(source.getParent());
        runner = mock(ConsultRunner.class);
    }

    /** The consultant runs in-process, to completion, when asked: the detached part is tested on its own. */
    private void consultantIs(String mode) throws Exception {
        configure("gemini: '" + FakeConsult.command(mode) + "'");
        doAnswer(invocation -> {
            ConsultRunner.Launch launch = invocation.getArgument(0);
            new ConsultWorker(paths, config, new ProcessRunner(), clock, Map.of()).run(launch.sessionId(), launch.agent());
            return null;
        }).when(runner).start(any());
    }

    private void configure(String geminiCommand) throws IOException, com.devmanchego.javaguard.config.ConfigException {
        Files.writeString(root.resolve("javaguard.yml"), "consult:\n  enabled: true\n  order: [gemini]\n  commands:\n    "
                + geminiCommand + "\n");
        config = ConfigLoader.load(ConfigSources.standard(root, Optional.empty(), root.resolve("home"), Map.of())).config();
    }

    private LoopGuard guard() {
        return new LoopGuard(paths, new LoopSettings(3, 6, 5, 12, 3), 5, clock, false,
                Optional.of(new HandoffService(paths, config, clock)), Optional.of(new ConsultSupport(config.consult(), Map.of())), runner, notified::add);
    }

    private static Finding finding() {
        return new Finding("JAVAC", "Compilation error", Category.COMPILATION, Severity.CRITICAL, "maven", Optional.of(Path.of(REL)), 3,
                1, 3, "cannot find symbol", "fix", Optional.empty(), Map.of(), Fingerprints.of("JAVAC", REL, "x"));
    }

    private GateResult run(boolean blocked, int attempt) throws IOException {
        Files.writeString(source, "class A { /* attempt " + attempt + " */ }");
        GateResult raw = new GateResult(GateKind.POST_EDIT, blocked ? Verdict.BLOCK : Verdict.PASS, blocked ? List.of(finding()) : List.of(),
                List.of(), List.of(), Optional.empty(), Optional.empty(), List.of(), Duration.ZERO);
        return guard().apply(raw, new LoopGuard.Input(SESSION, GateKind.POST_EDIT, Map.of(REL, source), false, "claude", Optional.empty()));
    }

    /** Blocked edits until the guard asks for the second opinion; returns the run that did. */
    private GateResult untilConsultation() throws IOException {
        for (int i = 1; i <= 12; i++) {
            GateResult result = run(true, i);
            if (result.escalation().map(e -> e.level()).orElse(0) == 2) {
                return result;
            }
            assertThat(result.verdict()).as("run %d", i).isEqualTo(Verdict.BLOCK);
        }
        throw new AssertionError("level 2 was never reached");
    }

    @Test
    void theConsultationIsRequestedOnceWithAHandoffAndItsSolutionIsInjectedExactlyOnce() throws Exception {
        consultantIs("ok");

        GateResult asked = untilConsultation();

        assertThat(asked.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(asked.escalation().orElseThrow().handoff()).startsWith(".javaguard/handoff/HANDOFF-sess-c-1");
        assertThat(asked.escalation().orElseThrow().guidance()).contains("A second opinion has been requested.");
        verify(runner, times(1)).start(new ConsultRunner.Launch(root, SESSION, com.devmanchego.javaguard.model.AgentKind.GEMINI));
        assertThat(notified).singleElement().satisfies(n -> assertThat(n.kind())
                .isEqualTo(com.devmanchego.javaguard.notify.Notification.Kind.CONSULTATION_STARTED));
        assertThat(ConsultStatus.read(paths.sessionDir(SESSION)).orElseThrow().state()).isEqualTo(ConsultStatus.State.DONE);

        GateResult delivered = run(true, 100);
        assertThat(delivered.solution()).hasValueSatisfying(s -> assertThat(s).contains("### Diagnosis").contains("JAVAGUARD_DISABLED=1"));
        assertThat(delivered.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(delivered.escalation().map(e -> e.level()).orElse(0)).isLessThanOrEqualTo(1);

        assertThat(run(true, 101).solution()).isEmpty();
        assertThat(run(false, 102).solution()).isEmpty();
        verify(runner, times(1)).start(any());
    }

    @Test
    void aFailedConsultationHaltsOnTheNextBlockedRun() throws Exception {
        consultantIs("fail");
        untilConsultation();
        assertThat(ConsultStatus.read(paths.sessionDir(SESSION)).orElseThrow().state()).isEqualTo(ConsultStatus.State.FAILED);

        GateResult next = run(true, 100);

        assertThat(next.verdict()).isEqualTo(Verdict.HALT);
        assertThat(next.escalation().orElseThrow().detector()).isEqualTo("consultation-failed");
        assertThat(next.escalation().orElseThrow().handoff()).isNotEmpty();
    }

    @Test
    void aConsultantThatNeverReportsIsGivenUpOnAfterItsDeadline() throws Exception {
        configure("gemini: '" + FakeConsult.command("ok") + "'");
        // The runner "starts" the worker, which then vanishes: the status stays RUNNING.
        untilConsultation();
        verify(runner, times(1)).start(any());

        assertThat(run(true, 100).verdict()).isEqualTo(Verdict.BLOCK); // still within the deadline
        verify(runner, times(1)).start(any()); // at most one in flight

        clock.advance(Duration.ofSeconds(config.consult().timeoutSeconds() + 120));
        GateResult late = run(true, 101);
        assertThat(late.verdict()).isEqualTo(Verdict.HALT);
        assertThat(late.escalation().orElseThrow().detector()).isEqualTo("consultation-failed");
    }

    @Test
    void withoutAnAvailableConsultantTheLadderGoesStraightToHalt() throws Exception {
        configure("gemini: 'no-such-tool-xyz <instruction>'");

        GateResult halted = null;
        for (int i = 1; i <= 12 && (halted == null || halted.verdict() != Verdict.HALT); i++) {
            halted = run(true, i);
        }

        assertThat(halted.verdict()).isEqualTo(Verdict.HALT);
        verify(runner, never()).start(any());
    }
}
