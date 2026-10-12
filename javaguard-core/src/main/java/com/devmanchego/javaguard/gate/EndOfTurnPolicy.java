package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.build.maven.MavenOutputParser;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.testing.TestFindings;

import java.util.ArrayList;
import java.util.List;

/**
 * END_OF_TURN treatment of findings and the resulting verdict (specification &sect;10.3 step 7, &sect;6.4).
 * Pure functions of the findings and the configuration.
 */
final class EndOfTurnPolicy {

    private final boolean unrelatedFailuresBlock;

    EndOfTurnPolicy(JavaGuardConfig config) {
        this.unrelatedFailuresBlock = config.tests().unrelatedFailures() == JavaGuardConfig.Policy.BLOCK;
    }

    FastGatePolicy.Partition partition(List<Finding> findings) {
        List<Finding> blocking = new ArrayList<>();
        List<Finding> advisory = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (Finding f : findings) {
            if (f.category() == Category.INFRASTRUCTURE) {
                notes.add(f.message()); // Never blocks: the build tool failing is not the agent's fault (section 6.4).
            } else if (isBlocking(f)) {
                blocking.add(f);
            } else {
                advisory.add(f);
            }
        }
        return new FastGatePolicy.Partition(blocking, advisory, notes);
    }

    Verdict verdict(List<Finding> blocking, List<Finding> advisory) {
        if (!blocking.isEmpty()) {
            return Verdict.BLOCK;
        }
        return advisory.isEmpty() ? Verdict.PASS : Verdict.ADVISE;
    }

    private boolean isBlocking(Finding f) {
        if (TestFindings.RULE_ID.equals(f.ruleId()) && TestFindings.POSSIBLY_PRE_EXISTING.equals(f.detail().get("classification"))) {
            return unrelatedFailuresBlock;
        }
        if ("JAVAC".equals(f.ruleId()) || MavenOutputParser.DEPENDENCY_RULE_ID.equals(f.ruleId())) {
            return true;
        }
        if (f.severity().isAtLeast(Severity.CRITICAL)) {
            return true;
        }
        return f.category() == Category.TEST_INTEGRITY && f.severity().isAtLeast(Severity.MAJOR);
    }
}
