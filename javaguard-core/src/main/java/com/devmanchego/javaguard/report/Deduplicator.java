package com.devmanchego.javaguard.report;

import com.devmanchego.javaguard.model.Finding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Merges duplicate findings from different engines (catalog &sect;4).
 *
 * <p>Two findings are duplicates when they share a fingerprint, or when they
 * share file + category and are within one line of each other and at least
 * one of them is a {@code JG-*} rule. The kept finding is the {@code JG-*}
 * one when there is one (its message is hand-written and specific); the
 * others are recorded in its {@code detail} map under
 * {@value #ALSO_REPORTED_BY}, as a comma-separated, sorted, de-duplicated
 * list of engine names. Relative order is preserved.
 */
public final class Deduplicator {

    public static final String ALSO_REPORTED_BY = "alsoReportedBy";

    private Deduplicator() {
    }

    public static List<Finding> merge(List<Finding> findings) {
        int n = findings.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (areDuplicates(findings.get(i), findings.get(j))) {
                    union(parent, i, j);
                }
            }
        }

        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }

        List<Finding> result = new ArrayList<>(groups.size());
        for (List<Integer> group : groups.values()) {
            result.add(group.size() == 1 ? findings.get(group.get(0)) : mergeGroup(findings, group));
        }
        return result;
    }

    private static Finding mergeGroup(List<Finding> findings, List<Integer> indices) {
        int keepIndex = indices.stream()
                .filter(i -> isJgRule(findings.get(i)))
                .findFirst()
                .orElse(indices.get(0));
        Finding keep = findings.get(keepIndex);

        TreeSet<String> otherEngines = new TreeSet<>();
        for (int i : indices) {
            if (i != keepIndex) {
                otherEngines.add(findings.get(i).engine());
            }
        }
        if (otherEngines.isEmpty()) {
            return keep;
        }
        Map<String, String> detail = new LinkedHashMap<>(keep.detail());
        detail.put(ALSO_REPORTED_BY, String.join(", ", otherEngines));
        return new Finding(keep.ruleId(), keep.title(), keep.category(), keep.severity(), keep.engine(), keep.file(),
                keep.line(), keep.column(), keep.endLine(), keep.message(), keep.remediation(), keep.snippet(),
                detail, keep.fingerprint());
    }

    private static boolean isJgRule(Finding f) {
        return f.ruleId().startsWith("JG-");
    }

    private static boolean areDuplicates(Finding a, Finding b) {
        if (a.fingerprint().equals(b.fingerprint())) {
            return true;
        }
        return a.file().isPresent() && a.file().equals(b.file())
                && a.category() == b.category()
                && Math.abs(a.line() - b.line()) <= 1
                && (isJgRule(a) || isJgRule(b));
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }
}
