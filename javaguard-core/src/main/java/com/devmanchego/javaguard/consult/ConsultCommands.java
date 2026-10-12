package com.devmanchego.javaguard.consult;

import com.devmanchego.javaguard.model.AgentKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The command lines that ask a second model for its opinion (specification &sect;15). The handoff goes to the child's
 * standard input; the argument list only carries the instruction and the read-only flags. Templates are token lists
 * with two placeholders, {@code <instruction>} and {@code <model>}; a user override in {@code consult.commands.<agent>}
 * is one string split on whitespace, with double quotes grouping words.
 */
public final class ConsultCommands {

    /** What the child is told; the handoff itself arrives on stdin. */
    public static final String INSTRUCTION = "Read the handoff document from stdin and answer the Request section.";

    private static final Map<AgentKind, List<String>> DEFAULTS = Map.of(
            AgentKind.GEMINI, List.of("gemini", "-p", "<instruction>", "--approval-mode", "plan"),
            AgentKind.CODEX, List.of("codex", "exec", "--sandbox", "read-only", "<instruction>"),
            AgentKind.CLAUDE, List.of("claude", "-p", "<instruction>", "--permission-mode", "plan", "--model", "<model>"));

    private ConsultCommands() {
    }

    /** The override for {@code agent} when there is one, else the built-in template; empty for an agent with neither. */
    public static List<String> template(AgentKind agent, Map<String, String> overrides) {
        String override = overrides.get(agent.cliName());
        if (override != null && !override.isBlank()) {
            return tokenize(override);
        }
        return DEFAULTS.getOrDefault(agent, List.of());
    }

    /** Placeholders replaced; the executable (first token) is left for the caller to resolve. */
    public static List<String> render(List<String> template, String model) {
        List<String> out = new ArrayList<>();
        for (String token : template) {
            out.add(token.replace("<instruction>", INSTRUCTION).replace("<model>", model));
        }
        return out;
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (char c : text.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    any = false;
                }
            } else {
                current.append(c);
                any = true;
            }
        }
        if (any) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
