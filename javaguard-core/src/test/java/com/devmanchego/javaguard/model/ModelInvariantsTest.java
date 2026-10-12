package com.devmanchego.javaguard.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Invariants of the Phase 1 domain contract (specification section 6, contracts section 2). */
class ModelInvariantsTest {

    @TempDir
    Path root;

    @Test
    void fileChangeRequiresAbsolutePath() {
        assertThatThrownBy(() -> FileChange.rewrite(Path.of("relative/A.java"), ChangeType.ADD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moveRequiresOriginAndOnlyMoveHasOne() {
        Path a = root.resolve("A.java");
        Path b = root.resolve("B.java");

        assertThatThrownBy(() -> new FileChange(a, ChangeType.MOVE, Optional.empty(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FileChange(a, ChangeType.UPDATE, Optional.of(b), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new FileChange(a, ChangeType.MOVE, Optional.of(b), List.of()).movedFrom()).contains(b);
    }

    @Test
    void fragmentsAreDefensivelyCopied() {
        java.util.ArrayList<EditFragment> list = new java.util.ArrayList<>();
        list.add(new EditFragment("a", "b", false));
        FileChange change = FileChange.edit(root.resolve("A.java"), list);
        list.clear();

        assertThat(change.fragments()).hasSize(1);
    }

    @Test
    void hookEventRequiresAbsoluteCwd() {
        assertThatThrownBy(() -> new HookEvent(AgentKind.CLAUDE, GateKind.END_OF_TURN, "s", Path.of("rel"),
                Optional.empty(), "", List.of(), false, Optional.empty(), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fingerprintIsLowerCaseHex() {
        assertThat(new Fingerprint("0a1b2c").value()).isEqualTo("0a1b2c");
        assertThatThrownBy(() -> new Fingerprint("0A1B")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Fingerprint("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findingFileMustBeProjectRelative() {
        assertThatThrownBy(() -> finding(Optional.of(root.resolve("A.java"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(finding(Optional.of(Path.of("src/A.java"))).file()).contains(Path.of("src/A.java"));
    }

    @Test
    void agentFeedbackRequiresTextForVerdictsThatShowIt() {
        assertThatThrownBy(() -> AgentFeedback.advise(" ", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentFeedback.block("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentFeedback.halt("r", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(AgentFeedback.pass().verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void effectiveSummaryFallsBackToFirstReportLine() {
        assertThat(AgentFeedback.advise("\n  first line  \nsecond", "").effectiveSummary()).isEqualTo("first line");
        assertThat(AgentFeedback.advise("report", " explicit ").effectiveSummary()).isEqualTo("explicit");
    }

    @Test
    void gateResultVerdictMustMatchFindings() {
        Finding f = finding(Optional.empty());

        assertThatThrownBy(() -> result(Verdict.BLOCK, List.of(), List.of(f)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(Verdict.PASS, List.of(), List.of(f)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(result(Verdict.BLOCK, List.of(f), List.of()).blocking()).hasSize(1);
    }

    @Test
    void verdictAndSeverityOrdering() {
        assertThat(Verdict.ADVISE.max(Verdict.BLOCK)).isEqualTo(Verdict.BLOCK);
        assertThat(Verdict.HALT.max(Verdict.PASS)).isEqualTo(Verdict.HALT);
        assertThat(Severity.BLOCKER.isAtLeast(Severity.MAJOR)).isTrue();
        assertThat(Severity.INFO.isAtLeast(Severity.MAJOR)).isFalse();
    }

    @Test
    void cliNamesRoundTrip() {
        for (GateKind g : GateKind.values()) {
            assertThat(GateKind.fromCliName(g.cliName().toUpperCase())).contains(g);
        }
        for (AgentKind a : AgentKind.values()) {
            assertThat(AgentKind.fromCliName(" " + a.cliName() + " ")).contains(a);
        }
        assertThat(GateKind.fromCliName("post_edit")).isEmpty();
        assertThat(AgentKind.fromCliName(null)).isEmpty();
    }

    private static Finding finding(Optional<Path> file) {
        return new Finding("JG-SEC-001", "SQL built by concatenation", Category.SECURITY, Severity.BLOCKER, "ast",
                file, 42, 5, 42, "msg", "fix", Optional.empty(), Map.of(), new Fingerprint("abc123"));
    }

    private static GateResult result(Verdict verdict, List<Finding> blocking, List<Finding> advisory) {
        return new GateResult(GateKind.POST_EDIT, verdict, blocking, advisory, List.of(), Optional.empty(),
                Optional.empty(), List.of(), Duration.ZERO);
    }
}
