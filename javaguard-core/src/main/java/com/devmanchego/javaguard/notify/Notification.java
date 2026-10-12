package com.devmanchego.javaguard.notify;

import com.devmanchego.javaguard.model.GateResult;

import java.util.Optional;

/**
 * Something the user should know about, away from the agent's conversation (specification &sect;16).
 *
 * @param kind    why
 * @param title   short, ASCII
 * @param message one line
 */
public record Notification(Kind kind, String title, String message) {

    public enum Kind { HALT, CONSULTATION_STARTED, CONSULTATION_FINISHED }

    private static final int MAX_PROBLEM_CHARS = 160;

    /** {@code JavaGuard halted <agent> in <project>: <problem>. Handoff: <path>} */
    public static Notification halt(String agent, String project, GateResult result) {
        String problem = result.blocking().stream().findFirst()
                .map(f -> f.ruleId() + " " + f.title() + f.file().map(p -> " in " + p.toString().replace('\\', '/')).orElse(""))
                .or(() -> result.escalation().map(e -> "stuck (" + e.detector() + ")"))
                .orElse("the agent is stuck");
        if (problem.length() > MAX_PROBLEM_CHARS) {
            problem = problem.substring(0, MAX_PROBLEM_CHARS) + "...";
        }
        Optional<String> handoff = result.escalation().map(e -> e.handoff()).filter(h -> !h.isEmpty());
        String message = "JavaGuard halted " + (agent.isEmpty() ? "the agent" : agent) + " in " + project + ": " + problem + "."
                + handoff.map(h -> " Handoff: " + h).orElse("");
        return new Notification(Kind.HALT, "JavaGuard halted " + (agent.isEmpty() ? "the agent" : agent), message);
    }

    public static Notification consultationStarted(String consultant, String project) {
        return new Notification(Kind.CONSULTATION_STARTED, "JavaGuard consultation",
                "JavaGuard asked " + consultant + " for a second opinion in " + project + ".");
    }

    public static Notification consultationFinished(String consultant, String project, boolean delivered, String detail) {
        return new Notification(Kind.CONSULTATION_FINISHED, "JavaGuard consultation",
                delivered ? consultant + " answered in " + project + "; the solution reaches the agent on its next gate run."
                        : "The consultation with " + consultant + " in " + project + " failed: " + detail);
    }
}
