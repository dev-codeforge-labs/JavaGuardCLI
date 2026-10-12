package com.devmanchego.javaguard.change;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Fingerprint;
import com.devmanchego.javaguard.model.Severity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scoping table (specification section 9.3): baseline fingerprints, current
 * findings and changed ranges determine what is reported. Written before
 * {@link NewFindingFilter}. Notation: {@code "a@10"} is a finding whose
 * fingerprint is derived from {@code "a"}, at line 10.
 */
class NewFindingFilterTest {

    static Stream<Arguments> table() {
        return Stream.of(
                //            baseline      current                      changed ranges   reported
                Arguments.of("no baseline: everything is new",
                        "", "a@10", "", "a@10"),
                Arguments.of("same finding moved into a changed range is still old",
                        "a", "a@12", "12-12", ""),
                Arguments.of("extra occurrence: the one inside the changed range is new",
                        "a", "a@3 a@20", "20-20", "a@20"),
                Arguments.of("extra occurrence, none in range: lowest line is reported",
                        "a", "a@3 a@20", "", "a@3"),
                Arguments.of("fixed findings are never reported",
                        "a a", "a@3", "", ""),
                Arguments.of("different fingerprint is new even outside changed ranges",
                        "a", "b@5", "", "b@5"),
                Arguments.of("unknown line (0) is new when not in baseline",
                        "", "a@0", "", "a@0"),
                Arguments.of("two extras, both in range",
                        "a", "a@5 a@6 a@7", "6-7", "a@6 a@7"),
                Arguments.of("two extras, one in range: in-range first, then lowest line; output keeps order",
                        "a", "a@5 a@6 a@7", "7-7", "a@5 a@7"),
                Arguments.of("mixed fingerprints",
                        "a b", "b@1 a@2 c@3 a@4", "4-4", "c@3 a@4"),
                Arguments.of("empty current",
                        "a", "", "1-5", ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("table")
    void scopes(String name, String baseline, String current, String ranges, String expected) {
        List<Finding> reported = NewFindingFilter.filter(findings(current), fingerprints(baseline), ranges(ranges));

        assertThat(reported.stream().map(NewFindingFilterTest::label).toList())
                .as(name)
                .containsExactlyElementsOf(tokens(expected));
    }

    // --- notation helpers ---------------------------------------------------

    private static List<String> tokens(String spec) {
        return spec.isBlank() ? List.of() : List.of(spec.trim().split("\\s+"));
    }

    private static Fingerprint fp(String id) {
        return new Fingerprint(Hashing.shortSha256(id, 16));
    }

    private static List<Fingerprint> fingerprints(String spec) {
        return tokens(spec).stream().map(NewFindingFilterTest::fp).toList();
    }

    private static List<Finding> findings(String spec) {
        List<Finding> out = new ArrayList<>();
        for (String token : tokens(spec)) {
            String[] parts = token.split("@");
            int line = Integer.parseInt(parts[1]);
            out.add(new Finding("JG-X", parts[0], Category.RELIABILITY, Severity.MAJOR, "ast",
                    Optional.of(Path.of("A.java")), line, 0, line, "m", "f", Optional.empty(), Map.of(), fp(parts[0])));
        }
        return out;
    }

    private static LineRanges ranges(String spec) {
        LineRanges.Builder builder = LineRanges.builder();
        for (String token : tokens(spec)) {
            String[] parts = token.split("-");
            builder.add(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }
        return builder.build();
    }

    private static String label(Finding f) {
        return f.title() + "@" + f.line();
    }
}
