package com.devmanchego.javaguard.change;

import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Fingerprint;
import com.devmanchego.javaguard.model.Suppression;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs file-local checks on one file and keeps only what the session
 * introduced (specification &sect;9.3, &sect;11.3). Pipeline, applied
 * identically to baseline and current content:
 * <ol>
 *   <li>analyze;</li>
 *   <li>configuration policy ({@code exclude}, {@code rules.<id>});</li>
 *   <li>source suppression markers;</li>
 * </ol>
 * then the current result minus the baseline result (multiset of
 * fingerprints, surplus chosen inside changed lines first), plus the
 * JG-SUP findings about suppressions added this session.
 *
 * <p>Applying suppressions on both sides before the difference is what makes
 * "the agent removed a suppression" surface the hidden finding as new, and
 * "a suppressed finding moved" stay silent.
 *
 * <p>Global checks (compilation, tests, dependencies) must not go through
 * here: a compile error anywhere is real.
 */
public final class ScopedAnalysis {

    /** A file-local check, deterministic for a given (path, content). */
    @FunctionalInterface
    public interface FileAnalyzer {
        List<Finding> analyze(String relativePath, String content);
    }

    /**
     * @param reported        findings the session introduced, then JG-SUP findings
     * @param changedLines    lines of the current file changed vs. the baseline
     * @param newSuppressions suppression markers added this session (user notices)
     */
    public record Result(List<Finding> reported, LineRanges changedLines, List<Suppression> newSuppressions) {
        public Result {
            reported = List.copyOf(reported);
            Objects.requireNonNull(changedLines, "changedLines");
            newSuppressions = List.copyOf(newSuppressions);
        }
    }

    private final BaselineFindingsCache cache;
    private final ConfigFindingPolicy policy;
    private final boolean allowAgentAddedSuppressions;

    public ScopedAnalysis(BaselineFindingsCache cache, ConfigFindingPolicy policy, boolean allowAgentAddedSuppressions) {
        this.cache = cache;
        this.policy = policy;
        this.allowAgentAddedSuppressions = allowAgentAddedSuppressions;
    }

    /**
     * @param analyzerKey identity of {@code analyzer} and its version; part of
     *                    the baseline cache key
     */
    public Result run(String relativePath, String baseline, String current, FileAnalyzer analyzer, String analyzerKey) {
        if (policy.isExcluded(relativePath)) {
            return new Result(List.of(), LineRanges.empty(), List.of());
        }
        LineRanges changed = ChangedLines.between(baseline, current);
        List<SuppressionMarker> baselineMarkers = SuppressionScanner.scan(baseline);
        List<SuppressionMarker> currentMarkers = SuppressionScanner.scan(current);

        SuppressionFilter.Outcome now = SuppressionFilter.apply(relativePath,
                policy.apply(analyzer.analyze(relativePath, current)), currentMarkers, baselineMarkers, changed,
                allowAgentAddedSuppressions);

        String key = BaselineFindingsCache.key(analyzerKey + "|" + policy.key(), relativePath, Hashing.sha256(baseline));
        List<Fingerprint> before = cache.get(key).orElseGet(() -> {
            List<Fingerprint> computed = SuppressionFilter.apply(relativePath,
                            policy.apply(analyzer.analyze(relativePath, baseline)), baselineMarkers, baselineMarkers,
                            LineRanges.empty(), true)
                    .kept().stream().map(Finding::fingerprint).toList();
            cache.put(key, computed);
            return computed;
        });

        List<Finding> reported = new ArrayList<>(NewFindingFilter.filter(now.kept(), before, changed));
        reported.addAll(now.suppressionFindings());
        return new Result(reported, changed, now.newSuppressions());
    }
}
