package com.devmanchego.javaguard.model;

import java.util.List;
import java.util.Objects;

/**
 * What a gate wants to tell the agent and the user, already rendered to
 * text, independent of any agent's wire format.
 *
 * <p>This is the boundary between the report renderer (Phase 3, which owns
 * wording, ranking and budgets) and the agent adapters (Phase 1, which own
 * channels: exit codes, stdout/stderr, JSON field names). Adapters never
 * compose report text; renderers never know about exit codes.
 *
 * @param verdict     gate verdict
 * @param report      agent-facing report; required for ADVISE and BLOCK
 * @param summary     one-line summary for the user (end-of-turn advisories);
 *                    when blank, the first non-blank line of {@code report} is used
 * @param haltMessage user-facing halt message; required for HALT
 * @param userNotices messages for the user only (e.g. suppressions added this session)
 * @param ringBell    ask the agent to ring the terminal bell, where supported
 */
public record AgentFeedback(
        Verdict verdict,
        String report,
        String summary,
        String haltMessage,
        List<String> userNotices,
        boolean ringBell
) {

    private static final AgentFeedback PASS = new AgentFeedback(Verdict.PASS, "", "", "", List.of(), false);

    public AgentFeedback {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(haltMessage, "haltMessage");
        userNotices = List.copyOf(Objects.requireNonNull(userNotices, "userNotices"));
        if ((verdict == Verdict.ADVISE || verdict == Verdict.BLOCK) && report.isBlank()) {
            throw new IllegalArgumentException(verdict + " requires a report");
        }
        if (verdict == Verdict.HALT && haltMessage.isBlank()) {
            throw new IllegalArgumentException("HALT requires a haltMessage");
        }
    }

    public static AgentFeedback pass() {
        return PASS;
    }

    public static AgentFeedback advise(String report, String summary) {
        return new AgentFeedback(Verdict.ADVISE, report, summary, "", List.of(), false);
    }

    public static AgentFeedback block(String report) {
        return new AgentFeedback(Verdict.BLOCK, report, "", "", List.of(), false);
    }

    public static AgentFeedback halt(String report, String haltMessage) {
        return new AgentFeedback(Verdict.HALT, report, "", haltMessage, List.of(), false);
    }

    public AgentFeedback withUserNotices(List<String> notices) {
        return new AgentFeedback(verdict, report, summary, haltMessage, notices, ringBell);
    }

    public AgentFeedback withBell(boolean bell) {
        return new AgentFeedback(verdict, report, summary, haltMessage, userNotices, bell);
    }

    /** {@link #summary()} or, when blank, the first non-blank line of {@link #report()}. */
    public String effectiveSummary() {
        if (!summary.isBlank()) {
            return summary.strip();
        }
        return report.lines().filter(l -> !l.isBlank()).findFirst().map(String::strip).orElse("");
    }
}
