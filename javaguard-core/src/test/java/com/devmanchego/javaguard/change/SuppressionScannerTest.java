package com.devmanchego.javaguard.change;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Specification section 11.3: line and element suppressions. */
class SuppressionScannerTest {

    @Test
    void lineMarkerOnItsOwnLineCoversItselfAndTheNextLine() {
        String source = """
                class A {
                    // javaguard:ignore JG-SEC-001 -- table name is a compile-time constant
                    String sql = "SELECT * FROM " + TABLE;
                }
                """;

        List<SuppressionMarker> markers = SuppressionScanner.scan(source);

        // covers lines 2..3 (itself and the next), marker written on line 2
        assertThat(markers).containsExactly(new SuppressionMarker("JG-SEC-001", SuppressionMarker.Kind.LINE, 2, 3, 2,
                Optional.of("table name is a compile-time constant")));
    }

    @Test
    void trailingLineMarker() {
        List<SuppressionMarker> markers = SuppressionScanner.scan(
                "class A {\n  void m() { foo(); } // javaguard:ignore JG-AI-001 -- CLI entry point\n}\n");

        assertThat(markers).singleElement().satisfies(m -> {
            assertThat(m.ruleId()).isEqualTo("JG-AI-001");
            assertThat(m.fromLine()).isEqualTo(2);
            assertThat(m.toLine()).isEqualTo(3);
        });
    }

    @Test
    void markerWithoutReasonHasEmptyReason() {
        assertThat(SuppressionScanner.scan("// javaguard:ignore JG-X\n").get(0).reason()).isEmpty();
        assertThat(SuppressionScanner.scan("// javaguard:ignore JG-X --   \n").get(0).reason()).isEmpty();
    }

    @Test
    void acceptsNonJgRuleIds() {
        assertThat(SuppressionScanner.scan("// javaguard:ignore PMD:CloseResource -- pooled\n").get(0).ruleId())
                .isEqualTo("PMD:CloseResource");
    }

    @Test
    void elementMarkerOnAMethodCoversTheWholeMethod() {
        String source = """
                class A {
                    @SuppressWarnings("javaguard:JG-REL-001") // legacy API forces this
                    void m() {
                        try { x(); } catch (Exception e) { }
                    }

                    void other() { }
                }
                """;

        List<SuppressionMarker> markers = SuppressionScanner.scan(source);

        assertThat(markers).containsExactly(new SuppressionMarker("JG-REL-001", SuppressionMarker.Kind.ELEMENT, 2, 5, 2,
                Optional.of("legacy API forces this")));
    }

    @Test
    void elementMarkerInAnArrayOnAField() {
        String source = """
                class A {
                    @SuppressWarnings({"unchecked", "javaguard:JG-CONC-002"}) // guarded by lock
                    private final java.util.Map<String, String> cache = new java.util.HashMap<>();
                }
                """;

        assertThat(SuppressionScanner.scan(source)).singleElement().satisfies(m -> {
            assertThat(m.ruleId()).isEqualTo("JG-CONC-002");
            assertThat(m.kind()).isEqualTo(SuppressionMarker.Kind.ELEMENT);
            assertThat(m.fromLine()).isEqualTo(2);
            assertThat(m.toLine()).isEqualTo(3);
        });
    }

    @Test
    void classLevelElementMarkerWithoutReason() {
        String source = """
                @SuppressWarnings("javaguard:JG-AI-001")
                class Main {
                    public static void main(String[] a) { System.out.println("x"); }
                }
                """;

        assertThat(SuppressionScanner.scan(source)).singleElement().satisfies(m -> {
            assertThat(m.fromLine()).isEqualTo(1);
            assertThat(m.toLine()).isEqualTo(4);
            assertThat(m.reason()).isEmpty();
        });
    }

    @Test
    void unparseableSourceStillYieldsLineMarkers() {
        String broken = """
                class A {
                    @SuppressWarnings("javaguard:JG-X") // r
                    void m( {
                    // javaguard:ignore JG-Y -- still found
                """;

        assertThat(SuppressionScanner.scan(broken)).extracting(SuppressionMarker::ruleId).containsExactly("JG-Y");
    }

    @Test
    void ordinarySuppressWarningsAreIgnored() {
        assertThat(SuppressionScanner.scan("@SuppressWarnings(\"unchecked\")\nclass A {}\n")).isEmpty();
    }

    @Test
    void markerCoversLine() {
        SuppressionMarker line = new SuppressionMarker("JG-X", SuppressionMarker.Kind.LINE, 5, 6, 5, Optional.empty());

        assertThat(line.covers(5)).isTrue();
        assertThat(line.covers(6)).isTrue();
        assertThat(line.covers(7)).isFalse();
        assertThat(line.covers(0)).isFalse();
    }
}
