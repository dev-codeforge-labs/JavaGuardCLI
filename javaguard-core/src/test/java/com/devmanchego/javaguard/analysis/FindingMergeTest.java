package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Catalog &sect;4 deduplication: same file + line (&plusmn;1) + category, one
 * side a JG rule, keeps the JG finding and records the other engine under
 * {@code detail.alsoReportedBy}; anything else survives untouched.
 */
class FindingMergeTest {

    private static Finding finding(String ruleId, String engine, Category category, int line) {
        String path = "src/main/java/Sample.java";
        return new Finding(ruleId, ruleId, category, Severity.MAJOR, engine, Optional.of(Path.of(path)),
                line, 0, line, "message for " + ruleId, "fix it", Optional.empty(), Map.of(),
                Fingerprints.of(ruleId, path, "message for " + ruleId));
    }

    @Test
    void pmdDuplicateOfJgFindingIsFoldedIntoAlsoReportedBy() {
        Finding jg = finding("JG-REL-003", "ast", Category.RELIABILITY, 10);
        Finding pmd = finding("PMD:CloseResource", "pmd", Category.RELIABILITY, 11);

        List<Finding> merged = FindingMerge.merge(List.of(jg), List.of(pmd));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).ruleId()).isEqualTo("JG-REL-003");
        assertThat(merged.get(0).detail()).containsEntry("alsoReportedBy", "pmd");
    }

    @Test
    void nonOverlappingPmdFindingSurvives() {
        Finding jg = finding("JG-SEC-001", "ast", Category.SECURITY, 5);
        Finding pmd = finding("PMD:EmptyCatchBlock", "pmd", Category.RELIABILITY, 50);

        List<Finding> merged = FindingMerge.merge(List.of(jg), List.of(pmd));

        assertThat(merged).hasSize(2);
        assertThat(merged).extracting(Finding::ruleId).containsExactlyInAnyOrder("JG-SEC-001", "PMD:EmptyCatchBlock");
    }

    @Test
    void differentCategoryOnSameLineDoesNotMerge() {
        Finding jg = finding("JG-PERF-001", "ast", Category.PERFORMANCE, 20);
        Finding pmd = finding("PMD:EmptyCatchBlock", "pmd", Category.RELIABILITY, 20);

        List<Finding> merged = FindingMerge.merge(List.of(jg), List.of(pmd));

        assertThat(merged).hasSize(2);
    }

    @Test
    void exactFingerprintDuplicateIsDropped() {
        Finding jg = finding("JG-REL-003", "ast", Category.RELIABILITY, 10);
        Finding sameFingerprint = finding("JG-REL-003", "pmd", Category.RELIABILITY, 999);

        List<Finding> merged = FindingMerge.merge(List.of(jg), List.of(sameFingerprint));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).detail()).doesNotContainKey("alsoReportedBy");
    }
}
