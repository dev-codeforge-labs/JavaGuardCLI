package com.devmanchego.javaguard.change;

import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Fingerprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports only findings the session introduced (specification &sect;9.3):
 * the multiset difference {@code C - B} of current over baseline
 * fingerprints.
 *
 * <p>When a fingerprint occurs more often now than in the baseline, the
 * surplus occurrences are chosen deterministically: those inside the changed
 * line ranges first, then by ascending line. The result keeps the original
 * order of {@code current}.
 */
public final class NewFindingFilter {

    private NewFindingFilter() {
    }

    public static List<Finding> filter(List<Finding> current, List<Fingerprint> baseline, LineRanges changed) {
        Map<Fingerprint, Integer> baselineCount = new HashMap<>();
        for (Fingerprint fp : baseline) {
            baselineCount.merge(fp, 1, Integer::sum);
        }

        Map<Fingerprint, List<Integer>> indicesByFingerprint = new LinkedHashMap<>();
        for (int i = 0; i < current.size(); i++) {
            indicesByFingerprint.computeIfAbsent(current.get(i).fingerprint(), k -> new ArrayList<>()).add(i);
        }

        Set<Integer> reported = new HashSet<>();
        indicesByFingerprint.forEach((fp, indices) -> {
            int surplus = indices.size() - baselineCount.getOrDefault(fp, 0);
            if (surplus <= 0) {
                return;
            }
            List<Integer> candidates = new ArrayList<>(indices);
            candidates.sort(Comparator
                    .comparing((Integer i) -> !changed.contains(current.get(i).line()))
                    .thenComparingInt(i -> current.get(i).line())
                    .thenComparingInt(i -> i));
            reported.addAll(candidates.subList(0, surplus));
        });

        List<Finding> result = new ArrayList<>();
        for (int i = 0; i < current.size(); i++) {
            if (reported.contains(i)) {
                result.add(current.get(i));
            }
        }
        return result;
    }
}
