package com.devmanchego.javaguard.report;

import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.AgentFeedback;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.Suppression;
import com.devmanchego.javaguard.model.Verdict;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Renders a {@link GateResult} into the agent-facing report text
 * (specification &sect;12.1, &sect;12.2): deduplicates, collapses compile
 * error cascades, ranks, and fits everything into the configured budget with
 * blocking findings never sacrificed for advisory ones.
 *
 * <p>This is the boundary the agent adapters (contracts &sect;2.1) build on:
 * it knows nothing about exit codes or JSON, only about producing the right
 * words within the right size.
 */
public final class AgentReportRenderer {

    private AgentReportRenderer() {
    }

    public static AgentFeedback render(GateResult result, JavaGuardConfig.Report config) {
        if (result.verdict() == Verdict.PASS) {
            List<String> notices = suppressionNotices(result.newSuppressions());
            if (result.solution().isPresent()) {
                // Nothing to fix, but a second opinion on an earlier problem is waiting for the agent.
                AgentFeedback advice = AgentFeedback.advise("JAVAGUARD ADVISE - second opinion - "
                        + result.gate().cliName() + "\n\n" + secondOpinion(result.solution().get()), "");
                return notices.isEmpty() ? advice : advice.withUserNotices(notices);
            }
            return notices.isEmpty() ? AgentFeedback.pass() : AgentFeedback.pass().withUserNotices(notices);
        }
        List<Finding> blocking = CompileErrorCollapser.collapse(Deduplicator.merge(result.blocking()));
        List<Finding> advisory = CompileErrorCollapser.collapse(Deduplicator.merge(result.advisory()));
        blocking.sort(FindingOrdering.BY_RANK);
        advisory.sort(FindingOrdering.BY_RANK);

        Budget budget = new Budget(config.maxFindings(), config.maxChars());
        StringBuilder report = new StringBuilder();
        appendHeader(report, result.verdict(), blocking.size(), advisory.size(), result.gate(), fileCount(blocking, advisory));
        result.solution().ifPresent(text -> report.append("\n\n").append(secondOpinion(text)));
        result.escalation().filter(e -> !e.guidance().isBlank())
                .ifPresent(e -> report.append("\n\n## Change of approach required\n\n").append(e.guidance()));
        budget.spendChars(report.length());

        int shown = 0;
        if (!blocking.isEmpty()) {
            String sectionTitle = "\n\n## Blocking - fix these before continuing";
            report.append(sectionTitle);
            budget.spendChars(sectionTitle.length());
            for (Finding f : blocking) {
                String entry = renderEntry(++shown, f);
                report.append('\n').append('\n').append(entry);
                budget.spendFinding(entry.length() + 2);
            }
        }

        int advisoryShown = 0;
        List<Finding> advisorySection = new ArrayList<>();
        for (Finding f : advisory) {
            String entry = renderEntry(shown + advisorySection.size() + 1, f);
            // Blocking is never sacrificed for advisory (specification section 12.2); advisory
            // itself stops once either budget is exhausted rather than being cut mid-entry.
            // remainingFindings() already reflects every finding added so far (blocking and
            // advisory alike): checking it alone is correct and must not be combined with a
            // separate count of advisorySection, or the two decrements would compound.
            if (budget.remainingFindings() <= 0 || !budget.fits(entry.length() + 2)) {
                break;
            }
            advisorySection.add(f);
            budget.spendFinding(entry.length() + 2);
        }
        if (!advisorySection.isEmpty()) {
            report.append("\n\n## Advisory - address if related to your change");
            for (Finding f : advisorySection) {
                report.append('\n').append('\n').append(renderEntry(++shown, f));
            }
            advisoryShown = advisorySection.size();
        }

        int omitted = advisory.size() - advisoryShown;
        if (omitted > 0) {
            report.append("\n\n(+").append(omitted).append(" advisory finding").append(omitted == 1 ? "" : "s")
                    .append(" omitted. ").append(fullReportReference(result.fullReport())).append(')');
        }

        report.append("\n\n").append(GUIDANCE);
        boolean testIntegrity = Stream.concat(blocking.stream(), advisory.stream())
                .anyMatch(f -> f.category() == Category.TEST_INTEGRITY);
        if (testIntegrity) {
            report.append("\n\n").append(TEST_INTEGRITY_GUIDANCE);
        }

        String text = config.ascii() ? AsciiFolder.fold(report.toString()) : report.toString();
        AgentFeedback feedback = result.verdict() == Verdict.HALT
                ? AgentFeedback.halt(text, haltMessageOf(result))
                : (result.verdict() == Verdict.BLOCK ? AgentFeedback.block(text) : AgentFeedback.advise(text, ""));
        return feedback.withUserNotices(suppressionNotices(result.newSuppressions()));
    }

    private static final String GUIDANCE = """
            Guidance: fix root causes. Keep the behavior that was asked for and rewrite the
            code so the finding disappears, even if the request itself describes the flagged
            pattern. Do not change JavaGuard's configuration or hooks. Do not weaken, disable
            or delete tests. Add "// javaguard:ignore <rule> -- <reason>" only for genuine
            false positives. If the only way to satisfy a rule contradicts what the user asked
            for, or the finding looks wrong, stop and explain it to the user, citing the rule,
            instead of retrying.""";

    /** Specification section 11.1, verbatim: shown whenever the report contains a test-integrity finding. */
    static final String TEST_INTEGRITY_GUIDANCE = """
            Test integrity: Tests define expected behavior. Do not weaken, disable or delete them to make the
            build pass. If a test is genuinely wrong, restore it and explain why in your reply to the user instead.""";

    private static void appendHeader(StringBuilder out, Verdict verdict, int blockingCount, int advisoryCount,
                                     GateKind gate, int fileCount) {
        out.append("JAVAGUARD ").append(verdict).append(" - ").append(blockingCount).append(" blocking, ")
                .append(advisoryCount).append(" advisory - ").append(gate.cliName()).append(" - ")
                .append(fileCount).append(" file(s)");
    }

    private static int fileCount(List<Finding> blocking, List<Finding> advisory) {
        Set<Path> files = new LinkedHashSet<>();
        blocking.forEach(f -> f.file().ifPresent(files::add));
        advisory.forEach(f -> f.file().ifPresent(files::add));
        return files.size();
    }

    private static String renderEntry(int index, Finding f) {
        if (f.detail().containsKey(CompileErrorCollapser.SUMMARY)) {
            return index + ". [" + f.ruleId() + "] " + f.file().map(AgentReportRenderer::slash).orElse("") + ": " + f.message();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(index).append(". [").append(f.ruleId()).append(' ').append(f.title()).append(']');
        f.file().ifPresent(file -> sb.append(' ').append(slash(file)).append(':').append(f.line()));
        f.snippet().filter(s -> !s.isBlank()).ifPresent(snippet -> {
            for (String line : snippet.split("\n", -1)) {
                sb.append("\n   > ").append(line);
            }
        });
        if (!f.message().isBlank()) {
            sb.append("\n   Problem: ").append(f.message());
            String cascade = f.detail().get(CompileErrorCollapser.CASCADE_COUNT);
            if (cascade != null) {
                sb.append(" (+").append(cascade).append(" more error(s) from the same missing symbol)");
            }
        }
        String trace = f.detail().get("trace");
        if (trace != null && !trace.isBlank()) {
            sb.append("\n   Trace: ").append(trace.replace("\n", "\n      "));
        }
        if (!f.remediation().isBlank()) {
            sb.append("\n   Fix: ").append(f.remediation());
        }
        return sb.toString();
    }

    private static String fullReportReference(java.util.Optional<Path> fullReport) {
        return fullReport.map(p -> "Full report: " + slash(p)).orElse("See the full report for details.");
    }

    private static List<String> suppressionNotices(List<Suppression> suppressions) {
        List<String> notices = new ArrayList<>();
        for (Suppression s : suppressions) {
            notices.add("New suppression added this session: " + s.ruleId() + " at " + slash(s.file()) + ":" + s.line());
        }
        return notices;
    }

    /** Agent-facing paths always use '/', even when rendered on Windows. */
    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }

    /** Specification section 14.4: the framing that makes the agent weigh the proposal instead of obeying it. */
    static final String SECOND_OPINION_INTRO = "A second model analysed the problem you are stuck on. Its proposal follows. "
            + "Evaluate it critically against the current code and apply it if correct.";

    private static final int MAX_SOLUTION_CHARS = 8000;

    private static String secondOpinion(String solution) {
        String text = solution.strip();
        if (text.length() > MAX_SOLUTION_CHARS) {
            text = text.substring(0, MAX_SOLUTION_CHARS) + "\n(... truncated; read the full solution file)";
        }
        return "## Second opinion\n\n" + SECOND_OPINION_INTRO + "\n\n" + text;
    }

    /** What the user reads when the agent is stopped (specification &sect;13.3 level 3). */
    static String haltMessageOf(GateResult result) {
        return result.escalation()
                .map(e -> "JavaGuard halted the agent: the blocking problems persisted for " + e.attempts()
                        + " attempts (detector: " + e.detector() + ")."
                        + (e.handoff().isBlank() ? "" : " Handoff: " + e.handoff() + ".")
                        + " Review .javaguard/reports/last-report.md before resuming.")
                .orElse("JavaGuard halted the agent. Review .javaguard/reports/last-report.md before resuming.");
    }

    /** Tracks remaining count/char budget as entries are added; blocking entries always spend it, never wait for it. */
    private static final class Budget {
        private int remainingFindings;
        private int remainingChars;

        Budget(int maxFindings, int maxChars) {
            this.remainingFindings = maxFindings;
            this.remainingChars = maxChars;
        }

        /** Charges chars only (header, section titles): does not consume a finding slot. */
        void spendChars(int chars) {
            remainingChars -= chars;
        }

        /** Charges one finding slot plus its chars. */
        void spendFinding(int chars) {
            remainingFindings--;
            remainingChars -= chars;
        }

        int remainingFindings() {
            return remainingFindings;
        }

        boolean fits(int chars) {
            return chars <= remainingChars;
        }
    }
}
