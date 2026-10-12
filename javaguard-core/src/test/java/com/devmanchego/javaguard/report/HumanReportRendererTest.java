package com.devmanchego.javaguard.report;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Fingerprint;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.model.Suppression;
import com.devmanchego.javaguard.model.Verdict;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Specification section 12.3: full detail, no caps. */
class HumanReportRendererTest {

    private static Finding finding(String ruleId, Category category, Severity severity, Path file, int line) {
        return new Finding(ruleId, "title " + ruleId, category, severity, "ast", Optional.of(file), line, 1, line,
                "message", "remediation", Optional.empty(), Map.of(), new Fingerprint(Integer.toHexString((ruleId + line).hashCode() & 0xFFFFFFF)));
    }

    @Test
    void includesEveryFindingWithNoCapEvenPastTheAgentBudget() {
        List<Finding> advisory = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            advisory.add(finding("JG-AI-" + i, Category.AI_DEBRIS, Severity.INFO, Path.of("F" + i + ".java"), 1));
        }
        GateResult r = new GateResult(GateKind.END_OF_TURN, Verdict.ADVISE, List.of(), advisory, List.of(),
                Optional.empty(), Optional.empty(), List.of(), Duration.ofSeconds(3));

        String md = HumanReportRenderer.render(r, Instant.parse("2026-09-28T10:00:00Z"));

        for (int i = 0; i < 30; i++) {
            assertThat(md).contains("JG-AI-" + i);
        }
        assertThat(md).contains("Advisory (30)");
    }

    @Test
    void includesTimingsAndSkippedCheckNotes() {
        GateResult r = new GateResult(GateKind.POST_EDIT, Verdict.PASS, List.of(), List.of(),
                List.of("Some checks were skipped: time budget exhausted (spotbugs)"), Optional.empty(),
                Optional.empty(), List.of(), Duration.ofMillis(1234));

        String md = HumanReportRenderer.render(r, Instant.parse("2026-09-28T10:00:00Z"));

        assertThat(md).contains("PT1.234S");
        assertThat(md).contains("Some checks were skipped: time budget exhausted (spotbugs)");
    }

    @Test
    void includesSnippetTraceAndAlsoReportedByDetail() {
        Finding f = new Finding("JG-SEC-001", "t", Category.SECURITY, Severity.BLOCKER, "ast",
                Optional.of(Path.of("A.java")), 1, 1, 1, "msg", "fix", Optional.of("String sql = ..."),
                Map.of("trace", "A -> B", Deduplicator.ALSO_REPORTED_BY, "pmd, spotbugs"), new Fingerprint("abc"));
        GateResult r = new GateResult(GateKind.POST_EDIT, Verdict.BLOCK, List.of(f), List.of(), List.of(),
                Optional.empty(), Optional.empty(), List.of(), Duration.ZERO);

        String md = HumanReportRenderer.render(r, Instant.now());

        assertThat(md).contains("String sql = ...");
        assertThat(md).contains("Trace: A -> B");
        assertThat(md).contains("Also reported by: pmd, spotbugs");
    }

    @Test
    void aMultiLineTraceIsRenderedAsACodeBlock() {
        Finding f = new Finding("TEST", "t", Category.TEST_FAILURE, Severity.CRITICAL, "surefire",
                Optional.of(Path.of("A.java")), 1, 1, 1, "msg", "fix", Optional.empty(),
                Map.of("trace", "Boom: x\n  at A.m(A.java:1)"), new Fingerprint("abc"));
        GateResult r = new GateResult(GateKind.END_OF_TURN, Verdict.BLOCK, List.of(f), List.of(), List.of(),
                Optional.empty(), Optional.empty(), List.of(), Duration.ZERO);

        String md = HumanReportRenderer.render(r, Instant.now());

        assertThat(md).contains("- Trace:\n\n```\nBoom: x\n  at A.m(A.java:1)\n```\n");
    }

    @Test
    void listsNewSuppressionsWithReasonOrPlaceholder() {
        List<Suppression> suppressions = List.of(
                new Suppression("JG-A", Path.of("src/A.java"), 5, Optional.of("false positive")),
                new Suppression("JG-B", Path.of("src/B.java"), 6, Optional.empty()));
        GateResult r = new GateResult(GateKind.POST_EDIT, Verdict.PASS, List.of(), List.of(), List.of(),
                Optional.empty(), Optional.empty(), suppressions, Duration.ZERO);

        String md = HumanReportRenderer.render(r, Instant.now());

        assertThat(md).contains("- JG-A at src/A.java:5 (false positive)");
        assertThat(md).contains("- JG-B at src/B.java:6 (no reason given)");
    }

    @Test
    void neverUsesBackslashesForPathsRegardlessOfPlatform() {
        Finding f = finding("JG-X", Category.RELIABILITY, Severity.MAJOR, Path.of("src", "main", "A.java"), 1);
        GateResult r = new GateResult(GateKind.POST_EDIT, Verdict.ADVISE, List.of(), List.of(f), List.of(),
                Optional.empty(), Optional.empty(), List.of(), Duration.ZERO);

        assertThat(HumanReportRenderer.render(r, Instant.now())).doesNotContain("\\").contains("src/main/A.java");
    }
}
