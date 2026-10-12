package com.devmanchego.javaguard.change;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.model.Suppression;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Specification section 11.3: which findings a marker hides, and what new markers produce. */
class SuppressionFilterTest {

    private static final String FILE = "src/A.java";

    private static Finding finding(String ruleId, int line) {
        return new Finding(ruleId, "t", Category.SECURITY, Severity.CRITICAL, "ast", Optional.of(Path.of(FILE)), line,
                1, line, "m", "f", Optional.empty(), Map.of(), Fingerprints.of(ruleId, FILE, "m" + line));
    }

    private static SuppressionMarker line(String ruleId, int at, String reason) {
        return new SuppressionMarker(ruleId, SuppressionMarker.Kind.LINE, at, at + 1, at, Optional.ofNullable(reason));
    }

    private static SuppressionFilter.Outcome apply(List<Finding> findings, List<SuppressionMarker> current,
                                                   List<SuppressionMarker> baseline, boolean allowAgentAdded) {
        return SuppressionFilter.apply(FILE, findings, current, baseline, LineRanges.empty(), allowAgentAdded);
    }

    @Test
    void lineMarkerHidesItsLineAndTheNextOnly() {
        List<Finding> findings = List.of(finding("JG-X", 5), finding("JG-X", 6), finding("JG-X", 7));
        List<SuppressionMarker> markers = List.of(line("JG-X", 5, "ok"));

        SuppressionFilter.Outcome out = apply(findings, markers, markers, true);

        assertThat(out.kept()).extracting(Finding::line).containsExactly(7);
    }

    @Test
    void markerForAnotherRuleHidesNothing() {
        List<SuppressionMarker> markers = List.of(line("JG-Y", 5, "ok"));

        assertThat(apply(List.of(finding("JG-X", 5)), markers, markers, true).kept()).hasSize(1);
    }

    @Test
    void elementMarkerHidesItsWholeRange() {
        SuppressionMarker element = new SuppressionMarker("JG-X", SuppressionMarker.Kind.ELEMENT, 10, 20, 10,
                Optional.of("ok"));
        List<Finding> findings = List.of(finding("JG-X", 15), finding("JG-X", 21));

        assertThat(apply(findings, List.of(element), List.of(element), true).kept())
                .extracting(Finding::line).containsExactly(21);
    }

    @Test
    void findingsWithUnknownLineAreNeverSuppressedByMarkers() {
        SuppressionMarker element = new SuppressionMarker("JG-X", SuppressionMarker.Kind.ELEMENT, 1, 100, 1, Optional.of("ok"));

        assertThat(apply(List.of(finding("JG-X", 0)), List.of(element), List.of(element), true).kept()).hasSize(1);
    }

    @Test
    void newMarkerWithoutReasonStillSuppressesButAddsJgSup001() {
        SuppressionFilter.Outcome out = apply(List.of(finding("JG-X", 5)), List.of(line("JG-X", 5, null)), List.of(), true);

        assertThat(out.kept()).isEmpty();
        assertThat(out.suppressionFindings()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("JG-SUP-001");
            assertThat(f.severity()).isEqualTo(Severity.MAJOR);
            assertThat(f.line()).isEqualTo(5);
        });
    }

    @Test
    void preExistingMarkerWithoutReasonIsNotReportedAgain() {
        List<SuppressionMarker> markers = List.of(line("JG-X", 5, null));

        assertThat(apply(List.of(), markers, markers, true).suppressionFindings()).isEmpty();
    }

    @Test
    void movedPreExistingMarkerIsNotNew() {
        SuppressionFilter.Outcome out = apply(List.of(), List.of(line("JG-X", 9, "ok")), List.of(line("JG-X", 5, "ok")), true);

        assertThat(out.newSuppressions()).isEmpty();
    }

    @Test
    void newMarkersAreListedForTheUser() {
        SuppressionFilter.Outcome out = apply(List.of(), List.of(line("JG-SEC-001", 41, "constant table name")),
                List.of(), true);

        assertThat(out.newSuppressions()).containsExactly(
                new Suppression("JG-SEC-001", Path.of(FILE), 41, Optional.of("constant table name")));
    }

    @Test
    void whenAgentAddedSuppressionsAreDisallowedNewMarkersDoNotSuppressAndBlock() {
        SuppressionMarker old = line("JG-Y", 2, "approved by user");
        SuppressionMarker added = line("JG-X", 5, "trust me");
        List<Finding> findings = List.of(finding("JG-Y", 2), finding("JG-X", 5));

        SuppressionFilter.Outcome out = apply(findings, List.of(old, added), List.of(old), false);

        assertThat(out.kept()).extracting(Finding::ruleId).containsExactly("JG-X");
        assertThat(out.suppressionFindings()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("JG-SUP-002");
            assertThat(f.severity()).isEqualTo(Severity.BLOCKER);
        });
    }

    @Test
    void surplusMarkerInsideTheChangedRangeIsTheNewOne() {
        List<SuppressionMarker> current = List.of(line("JG-X", 3, "r"), line("JG-X", 30, "r"));
        LineRanges changed = LineRanges.builder().add(30, 31).build();

        SuppressionFilter.Outcome out = SuppressionFilter.apply(FILE, List.of(), current, List.of(line("JG-X", 3, "r")),
                changed, true);

        assertThat(out.newSuppressions()).extracting(Suppression::line).containsExactly(30);
    }
}
