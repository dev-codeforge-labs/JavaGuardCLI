package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.analysis.syntax.SynCheck;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.model.Verdict;

import java.util.ArrayList;
import java.util.List;

/**
 * POST_EDIT treatment of findings and the resulting verdict (specification
 * &sect;10.2 step 9 and &sect;6.4). Pure functions of the findings and the configuration.
 */
public final class FastGatePolicy {

    /** Prefix on compile errors reported before the end-of-turn gate enforces them. */
    public static final String COMPILE_PREFIX = "Compilation (will be enforced at end of turn): ";

    private static final String JAVAC_RULE_ID = "JAVAC";

    /** Findings split into the two reporting classes; INFRASTRUCTURE findings become notes. */
    public record Partition(List<Finding> blocking, List<Finding> advisory, List<String> infrastructureNotes) {
        public Partition {
            blocking = List.copyOf(blocking);
            advisory = List.copyOf(advisory);
            infrastructureNotes = List.copyOf(infrastructureNotes);
        }
    }

    private final JavaGuardConfig.Policy compileErrors;
    private final boolean adviseOnPostEdit;

    public FastGatePolicy(JavaGuardConfig config) {
        this.compileErrors = config.fastGate().compileErrors();
        this.adviseOnPostEdit = config.report().adviseOnPostEdit();
    }

    public Partition partition(List<Finding> findings) {
        List<Finding> blocking = new ArrayList<>();
        List<Finding> advisory = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (Finding f : findings) {
            if (f.category() == Category.INFRASTRUCTURE) {
                // Never blocks; the user sees it, the agent does not (specification section 6.4 rule 6).
                notes.add(f.message());
            } else if (isCompileError(f)) {
                Finding prefixed = withCompilePrefix(f);
                (compileErrors == JavaGuardConfig.Policy.BLOCK ? blocking : advisory).add(prefixed);
            } else if (isBlocking(f)) {
                blocking.add(f);
            } else {
                advisory.add(f);
            }
        }
        return new Partition(blocking, advisory, notes);
    }

    public Verdict verdict(List<Finding> blocking, List<Finding> advisory) {
        if (!blocking.isEmpty()) {
            return Verdict.BLOCK;
        }
        return !advisory.isEmpty() && adviseOnPostEdit ? Verdict.ADVISE : Verdict.PASS;
    }

    private static boolean isBlocking(Finding f) {
        if (SynCheck.RULE_ID.equals(f.ruleId()) || f.severity() == Severity.BLOCKER) {
            return true;
        }
        return f.category() == Category.TEST_INTEGRITY && f.severity().isAtLeast(Severity.CRITICAL);
    }

    private static boolean isCompileError(Finding f) {
        return JAVAC_RULE_ID.equals(f.ruleId());
    }

    private static Finding withCompilePrefix(Finding f) {
        if (f.message().startsWith(COMPILE_PREFIX)) {
            return f;
        }
        return new Finding(f.ruleId(), f.title(), f.category(), f.severity(), f.engine(), f.file(), f.line(),
                f.column(), f.endLine(), COMPILE_PREFIX + f.message(), f.remediation(), f.snippet(), f.detail(),
                f.fingerprint());
    }
}
