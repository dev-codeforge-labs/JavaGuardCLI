package com.devmanchego.javaguard.report;

import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.Suppression;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Full-detail Markdown report for humans (specification &sect;12.3): every
 * finding (deduplicated, but never collapsed or truncated the way the
 * agent-facing text is), plus timings and skipped-check notes.
 */
public final class HumanReportRenderer {

    private HumanReportRenderer() {
    }

    public static String render(GateResult result, Instant generatedAt) {
        List<Finding> blocking = Deduplicator.merge(result.blocking());
        List<Finding> advisory = Deduplicator.merge(result.advisory());
        blocking.sort(FindingOrdering.BY_RANK);
        advisory.sort(FindingOrdering.BY_RANK);

        StringBuilder md = new StringBuilder();
        md.append("# JavaGuard report - ").append(result.gate().cliName()).append(" - ").append(result.verdict()).append("\n\n");
        md.append("- Generated: ").append(generatedAt).append('\n');
        md.append("- Elapsed: ").append(result.elapsed()).append('\n');

        appendSection(md, "Blocking", blocking, 1);
        appendSection(md, "Advisory", advisory, blocking.size() + 1);

        result.escalation().ifPresent(e -> {
            md.append("\n## Escalation\n\n- Level ").append(e.level()).append(" (detector: ").append(e.detector())
                    .append(") after ").append(e.attempts()).append(" attempt(s)\n");
            if (!e.guidance().isBlank()) {
                md.append("- Guidance to the agent: ").append(e.guidance()).append('\n');
            }
        });
        if (!result.notes().isEmpty()) {
            md.append("\n## Notes\n\n");
            result.notes().forEach(n -> md.append("- ").append(n).append('\n'));
        }
        if (!result.newSuppressions().isEmpty()) {
            md.append("\n## New suppressions this session\n\n");
            for (Suppression s : result.newSuppressions()) {
                md.append("- ").append(s.ruleId()).append(" at ").append(slash(s.file())).append(':').append(s.line())
                        .append(" (").append(s.reason().filter(r -> !r.isBlank()).orElse("no reason given")).append(")\n");
            }
        }
        return md.toString();
    }

    private static void appendSection(StringBuilder md, String title, List<Finding> findings, int startIndex) {
        md.append("\n## ").append(title).append(" (").append(findings.size()).append(")\n");
        int index = startIndex;
        for (Finding f : findings) {
            md.append("\n### ").append(index++).append(". [").append(f.ruleId()).append(' ').append(f.title()).append(']');
            f.file().ifPresent(file -> md.append(' ').append(slash(file)).append(':').append(f.line()));
            md.append('\n');
            md.append("- Severity: ").append(f.severity()).append('\n');
            md.append("- Category: ").append(f.category()).append('\n');
            md.append("- Engine: ").append(f.engine()).append('\n');
            if (!f.message().isBlank()) {
                md.append("- Problem: ").append(f.message()).append('\n');
            }
            if (!f.remediation().isBlank()) {
                md.append("- Fix: ").append(f.remediation()).append('\n');
            }
            f.snippet().filter(s -> !s.isBlank()).ifPresent(s -> md.append("- Snippet:\n\n```\n").append(s).append("\n```\n"));
            for (Map.Entry<String, String> d : f.detail().entrySet()) {
                if (Deduplicator.ALSO_REPORTED_BY.equals(d.getKey())) {
                    md.append("- Also reported by: ").append(d.getValue()).append('\n');
                } else if ("trace".equals(d.getKey())) {
                    md.append(d.getValue().contains("\n")
                            ? "- Trace:\n\n```\n" + d.getValue() + "\n```\n"
                            : "- Trace: " + d.getValue() + "\n");
                }
            }
        }
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }
}
