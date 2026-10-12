package com.devmanchego.javaguard.report;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Fingerprint;
import com.devmanchego.javaguard.model.Severity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Catalog section 4. */
class DeduplicatorTest {

    private static Finding finding(String ruleId, String engine, Category category, Optional<Path> file, int line,
                                   String fingerprint) {
        return new Finding(ruleId, "title", category, Severity.MAJOR, engine, file, line, 0, line, "msg", "fix",
                Optional.empty(), Map.of(), new Fingerprint(fingerprint));
    }

    @Test
    void sameFingerprintIsMergedEvenAcrossDifferentEngines() {
        Finding a = finding("PMD:CloseResource", "pmd", Category.RELIABILITY, Optional.of(Path.of("A.java")), 10, "abc123");
        Finding b = finding("SB:OBL_UNSATISFIED_OBLIGATION", "spotbugs", Category.RELIABILITY, Optional.of(Path.of("A.java")), 10, "abc123");

        List<Finding> merged = Deduplicator.merge(List.of(a, b));

        assertThat(merged).hasSize(1);
    }

    @Test
    void jgRuleIsKeptOverNonJgRuleWithinOneLineSameFileAndCategory() {
        Finding pmd = finding("PMD:CloseResource", "pmd", Category.RELIABILITY, Optional.of(Path.of("A.java")), 10, "f1");
        Finding jg = finding("JG-REL-003", "ast", Category.RELIABILITY, Optional.of(Path.of("A.java")), 11, "f2");

        List<Finding> merged = Deduplicator.merge(List.of(pmd, jg));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).ruleId()).isEqualTo("JG-REL-003");
        assertThat(merged.get(0).detail()).containsEntry(Deduplicator.ALSO_REPORTED_BY, "pmd");
    }

    @Test
    void alsoReportedByListsEveryOtherEngineSortedAndDeduplicated() {
        Finding jg = finding("JG-REL-003", "ast", Category.RELIABILITY, Optional.of(Path.of("A.java")), 10, "f1");
        Finding sb = finding("SB:X", "spotbugs", Category.RELIABILITY, Optional.of(Path.of("A.java")), 10, "f2");
        Finding pmd = finding("PMD:Y", "pmd", Category.RELIABILITY, Optional.of(Path.of("A.java")), 10, "f3");
        Finding pmd2 = finding("PMD:Z", "pmd", Category.RELIABILITY, Optional.of(Path.of("A.java")), 11, "f4");

        List<Finding> merged = Deduplicator.merge(List.of(jg, sb, pmd, pmd2));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).detail()).containsEntry(Deduplicator.ALSO_REPORTED_BY, "pmd, spotbugs");
    }

    @Test
    void differentCategoryIsNeverMergedByProximityAlone() {
        Finding jg = finding("JG-REL-003", "ast", Category.RELIABILITY, Optional.of(Path.of("A.java")), 10, "f1");
        Finding other = finding("PMD:Y", "pmd", Category.PERFORMANCE, Optional.of(Path.of("A.java")), 10, "f2");

        assertThat(Deduplicator.merge(List.of(jg, other))).hasSize(2);
    }

    @Test
    void differentFileIsNeverMergedByProximityAlone() {
        Finding jg = finding("JG-REL-003", "ast", Category.RELIABILITY, Optional.of(Path.of("A.java")), 10, "f1");
        Finding other = finding("PMD:Y", "pmd", Category.RELIABILITY, Optional.of(Path.of("B.java")), 10, "f2");

        assertThat(Deduplicator.merge(List.of(jg, other))).hasSize(2);
    }

    @Test
    void twoLinesApartIsNotAMatch() {
        Finding jg = finding("JG-REL-003", "ast", Category.RELIABILITY, Optional.of(Path.of("A.java")), 10, "f1");
        Finding other = finding("PMD:Y", "pmd", Category.RELIABILITY, Optional.of(Path.of("A.java")), 12, "f2");

        assertThat(Deduplicator.merge(List.of(jg, other))).hasSize(2);
    }

    @Test
    void twoNonJgFindingsNearEachOtherAreNotMergedWithoutAFingerprintMatch() {
        Finding a = finding("PMD:X", "pmd", Category.RELIABILITY, Optional.of(Path.of("A.java")), 10, "f1");
        Finding b = finding("SB:Y", "spotbugs", Category.RELIABILITY, Optional.of(Path.of("A.java")), 10, "f2");

        assertThat(Deduplicator.merge(List.of(a, b))).hasSize(2);
    }

    @Test
    void findingsWithoutAFileAreNeverMergedByProximity() {
        Finding a = finding("JG-X", "ast", Category.RELIABILITY, Optional.empty(), 0, "f1");
        Finding b = finding("PMD:Y", "pmd", Category.RELIABILITY, Optional.empty(), 0, "f2");

        assertThat(Deduplicator.merge(List.of(a, b))).hasSize(2);
    }

    @Test
    void preservesRelativeOrderOfDistinctFindings() {
        Finding a = finding("JG-A", "ast", Category.SECURITY, Optional.of(Path.of("A.java")), 1, "f1");
        Finding b = finding("JG-B", "ast", Category.SECURITY, Optional.of(Path.of("B.java")), 1, "f2");
        Finding c = finding("JG-C", "ast", Category.SECURITY, Optional.of(Path.of("C.java")), 1, "f3");

        assertThat(Deduplicator.merge(List.of(a, b, c))).extracting(Finding::ruleId).containsExactly("JG-A", "JG-B", "JG-C");
    }

    @Test
    void emptyListStaysEmpty() {
        assertThat(Deduplicator.merge(List.of())).isEmpty();
    }
}
