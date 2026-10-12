package com.devmanchego.javaguard.change;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.model.Suppression;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Applies source suppression markers to a file's findings (specification
 * &sect;11.3) and reports what the session changed about them.
 *
 * <ul>
 *   <li>A marker hides findings of its rule on the lines it covers; findings
 *       with an unknown line (0) are never hidden.</li>
 *   <li>A marker is <em>new</em> when its identity (rule, kind, reason) occurs
 *       more often now than in the baseline; line moves never make a marker
 *       new. Surplus markers inside changed ranges are taken as the new ones.</li>
 *   <li>A new marker without a reason still suppresses and adds
 *       {@code JG-SUP-001} (MAJOR). Pre-existing ones are not re-reported.</li>
 *   <li>With {@code suppressions.allowAgentAdded: false} a new marker does
 *       <em>not</em> suppress, and adds {@code JG-SUP-002} (BLOCKER):
 *       otherwise the agent would silence the guard anyway.</li>
 * </ul>
 */
public final class SuppressionFilter {

    public static final String MISSING_REASON_RULE = "JG-SUP-001";
    public static final String AGENT_ADDED_RULE = "JG-SUP-002";

    /**
     * @param kept                findings not hidden by an effective marker
     * @param suppressionFindings JG-SUP-001/002 findings about new markers
     * @param newSuppressions     markers added this session, for user notices
     */
    public record Outcome(List<Finding> kept, List<Finding> suppressionFindings, List<Suppression> newSuppressions) {
        public Outcome {
            kept = List.copyOf(kept);
            suppressionFindings = List.copyOf(suppressionFindings);
            newSuppressions = List.copyOf(newSuppressions);
        }
    }

    private SuppressionFilter() {
    }

    public static Outcome apply(String relativePath, List<Finding> findings, List<SuppressionMarker> current,
                                List<SuppressionMarker> baseline, LineRanges changed, boolean allowAgentAdded) {
        Set<SuppressionMarker> added = newMarkers(current, baseline, changed);

        List<SuppressionMarker> effective = allowAgentAdded
                ? current
                : current.stream().filter(m -> !added.contains(m)).toList();
        List<Finding> kept = new ArrayList<>();
        for (Finding f : findings) {
            boolean hidden = effective.stream().anyMatch(m -> m.ruleId().equals(f.ruleId()) && m.covers(f.line()));
            if (!hidden) {
                kept.add(f);
            }
        }

        List<Finding> suppressionFindings = new ArrayList<>();
        List<Suppression> notices = new ArrayList<>();
        for (SuppressionMarker m : current) {
            if (!added.contains(m)) {
                continue;
            }
            notices.add(new Suppression(m.ruleId(), Path.of(relativePath), m.markerLine(), m.reason()));
            if (!allowAgentAdded) {
                suppressionFindings.add(finding(relativePath, m, AGENT_ADDED_RULE, "Agent-added suppression",
                        Severity.BLOCKER,
                        "New suppression of " + m.ruleId() + " added this session; agent-added suppressions are "
                                + "disabled (suppressions.allowAgentAdded=false).",
                        "Remove the suppression and fix the finding, or ask the user to approve it."));
            } else if (m.reason().isEmpty()) {
                suppressionFindings.add(finding(relativePath, m, MISSING_REASON_RULE, "Suppression without reason",
                        Severity.MAJOR,
                        "Suppression of " + m.ruleId() + " has no reason.",
                        "Add \" -- <reason>\" explaining why this finding is a false positive."));
            }
        }
        return new Outcome(kept, suppressionFindings, notices);
    }

    /** Surplus markers per identity; in-range first, then ascending line (same policy as findings, section 9.3). */
    private static Set<SuppressionMarker> newMarkers(List<SuppressionMarker> current, List<SuppressionMarker> baseline,
                                                     LineRanges changed) {
        Map<String, Integer> baselineCount = new HashMap<>();
        baseline.forEach(m -> baselineCount.merge(m.identity(), 1, Integer::sum));
        Map<String, List<SuppressionMarker>> byIdentity = new LinkedHashMap<>();
        current.forEach(m -> byIdentity.computeIfAbsent(m.identity(), k -> new ArrayList<>()).add(m));

        List<SuppressionMarker> added = new ArrayList<>();
        byIdentity.forEach((identity, markers) -> {
            int surplus = markers.size() - baselineCount.getOrDefault(identity, 0);
            if (surplus > 0) {
                List<SuppressionMarker> ordered = new ArrayList<>(markers);
                ordered.sort(Comparator.comparing((SuppressionMarker m) -> !changed.contains(m.markerLine()))
                        .thenComparingInt(SuppressionMarker::markerLine));
                added.addAll(ordered.subList(0, surplus));
            }
        });
        // Identity-based set: records with equal components are the same marker occurrence
        // only if they are the same line, which markerLine already distinguishes.
        return added.stream().collect(Collectors.toSet());
    }

    private static Finding finding(String relativePath, SuppressionMarker m, String ruleId, String title,
                                   Severity severity, String message, String remediation) {
        return new Finding(ruleId, title, Category.SUPPRESSION, severity, "javaguard", Optional.of(Path.of(relativePath)),
                m.markerLine(), 1, m.markerLine(), message, remediation, Optional.empty(), Map.of(),
                Fingerprints.of(ruleId, relativePath, message));
    }
}
