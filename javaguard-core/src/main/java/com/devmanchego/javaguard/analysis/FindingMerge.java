package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Finding;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Merges findings from different engines on the same file (catalog &sect;4):
 * two findings are duplicates when they share file + line (&plusmn;1) +
 * category and one of them is a JG rule, or when they share the same
 * fingerprint. The JG finding is kept; {@code detail.alsoReportedBy} lists
 * the other engines that also flagged it, comma-separated.
 */
public final class FindingMerge {

    private FindingMerge() {
    }

    /** @param jgFindings AST-engine findings (all {@code JG-*} rule ids) */
    public static List<Finding> merge(List<Finding> jgFindings, List<Finding> otherFindings) {
        List<Finding> jg = new ArrayList<>(jgFindings);
        List<Finding> survivors = new ArrayList<>();
        Map<Finding, TreeSet<String>> alsoReportedBy = new IdentityHashMap<>();

        for (Finding other : otherFindings) {
            Finding duplicateOf = findDuplicate(jg, other);
            if (duplicateOf != null) {
                alsoReportedBy.computeIfAbsent(duplicateOf, k -> new TreeSet<>()).add(other.engine());
            } else if (!hasSameFingerprint(jg, other)) {
                survivors.add(other);
            }
        }

        List<Finding> merged = new ArrayList<>();
        for (Finding f : jg) {
            TreeSet<String> also = alsoReportedBy.get(f);
            merged.add(also == null || also.isEmpty() ? f : withAlsoReportedBy(f, also));
        }
        merged.addAll(survivors);
        return merged;
    }

    private static Finding findDuplicate(List<Finding> jgFindings, Finding candidate) {
        if (candidate.file().isEmpty()) {
            return null;
        }
        for (Finding jgFinding : jgFindings) {
            if (jgFinding.file().isEmpty() || !jgFinding.file().get().equals(candidate.file().get())) {
                continue;
            }
            if (jgFinding.category() != candidate.category()) {
                continue;
            }
            if (Math.abs(jgFinding.line() - candidate.line()) <= 1) {
                return jgFinding;
            }
        }
        return null;
    }

    private static boolean hasSameFingerprint(List<Finding> jgFindings, Finding candidate) {
        return jgFindings.stream().anyMatch(f -> f.fingerprint().equals(candidate.fingerprint()));
    }

    private static Finding withAlsoReportedBy(Finding f, TreeSet<String> engines) {
        Map<String, String> detail = new LinkedHashMap<>(f.detail());
        detail.put("alsoReportedBy", String.join(",", engines));
        return new Finding(f.ruleId(), f.title(), f.category(), f.severity(), f.engine(), f.file(), f.line(),
                f.column(), f.endLine(), f.message(), f.remediation(), f.snippet(), detail, f.fingerprint());
    }
}
