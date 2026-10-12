package com.devmanchego.javaguard.compile;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 6, specification section 8.2 step 6: the in-process compilation confidence guard. */
class InProcessConfidenceGuardTest {

    private static Finding finding(Severity severity) {
        return new Finding("JAVAC", "Compilation error", Category.COMPILATION, severity, "javac", Optional.empty(),
                5, 1, 5, "cannot find symbol", "Fix the compile error.", Optional.empty(), Map.of(),
                new com.devmanchego.javaguard.model.Fingerprint("abc123"));
    }

    @Test
    void moduleStartsReliable() {
        InProcessConfidenceGuard guard = new InProcessConfidenceGuard();

        assertThat(guard.isUnreliable("moduleA")).isFalse();
        assertThat(guard.apply("moduleA", finding(Severity.CRITICAL)).severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void recordDiscrepancyMarksTheModuleUnreliable() {
        InProcessConfidenceGuard guard = new InProcessConfidenceGuard();

        guard.recordDiscrepancy("moduleA");

        assertThat(guard.isUnreliable("moduleA")).isTrue();
    }

    @Test
    void applyDowngradesToInfoOnceUnreliable() {
        InProcessConfidenceGuard guard = new InProcessConfidenceGuard();
        guard.recordDiscrepancy("moduleA");

        Finding downgraded = guard.apply("moduleA", finding(Severity.CRITICAL));

        assertThat(downgraded.severity()).isEqualTo(Severity.INFO);
        assertThat(downgraded.remediation()).contains("downgraded");
        assertThat(downgraded.ruleId()).isEqualTo("JAVAC");
    }

    @Test
    void onlyTheRecordedModuleIsAffected() {
        InProcessConfidenceGuard guard = new InProcessConfidenceGuard();
        guard.recordDiscrepancy("moduleA");

        assertThat(guard.isUnreliable("moduleB")).isFalse();
        assertThat(guard.apply("moduleB", finding(Severity.CRITICAL)).severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void alreadyInfoFindingsPassThroughUnchanged() {
        InProcessConfidenceGuard guard = new InProcessConfidenceGuard();
        guard.recordDiscrepancy("moduleA");
        Finding infoFinding = finding(Severity.INFO);

        assertThat(guard.apply("moduleA", infoFinding)).isSameAs(infoFinding);
    }
}
