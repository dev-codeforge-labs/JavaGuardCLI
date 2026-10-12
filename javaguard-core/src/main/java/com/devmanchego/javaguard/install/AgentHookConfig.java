package com.devmanchego.javaguard.install;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure merge logic for the hook files of Gemini CLI ({@code .gemini/settings.json}, contracts &sect;4.4) and Codex
 * ({@code .codex/hooks.json}, &sect;5.4): the same guarantees as {@link ClaudeHookConfig} - JavaGuard's own entries are
 * added, updated and removed, nothing else is touched, installing twice gives the same document - for agents that differ
 * only in event names, timeout units and entry fields.
 *
 * <p>An entry is JavaGuard's when its command line (or Codex's {@code commandWindows}) contains
 * {@code hook --agent <agent> --gate <gate>}.
 */
public final class AgentHookConfig {

    /** The two agents handled here. */
    public enum Agent {
        GEMINI("gemini"), CODEX("codex");

        private final String cliName;

        Agent(String cliName) {
            this.cliName = cliName;
        }

        public String cliName() {
            return cliName;
        }
    }

    /** The JavaGuard gates, with the event each agent fires for them. */
    public enum Gate {
        PRE_EDIT("pre-edit", "BeforeTool", "PreToolUse", "write_file|replace", "^apply_patch$", 10, "JavaGuard: taking a baseline..."),
        POST_EDIT("post-edit", "AfterTool", "PostToolUse", "write_file|replace", "^apply_patch$", 30, "JavaGuard: checking patch..."),
        END_OF_TURN("end-of-turn", "AfterAgent", "Stop", null, null, 600, "JavaGuard: compiling and running affected tests..."),
        /** Records the working tree when a turn begins; silent, so no status message. */
        TURN_START("turn-start", "BeforeAgent", "UserPromptSubmit", null, null, 10, null);

        private final String cliName;
        private final String geminiEvent;
        private final String codexEvent;
        private final String geminiMatcher;
        private final String codexMatcher;
        private final int timeoutSeconds;
        private final String statusMessage;

        Gate(String cliName, String geminiEvent, String codexEvent, String geminiMatcher, String codexMatcher, int timeoutSeconds,
             String statusMessage) {
            this.cliName = cliName;
            this.geminiEvent = geminiEvent;
            this.codexEvent = codexEvent;
            this.geminiMatcher = geminiMatcher;
            this.codexMatcher = codexMatcher;
            this.timeoutSeconds = timeoutSeconds;
            this.statusMessage = statusMessage;
        }

        public String cliName() {
            return cliName;
        }

        public String event(Agent agent) {
            return agent == Agent.GEMINI ? geminiEvent : codexEvent;
        }

        String matcher(Agent agent) {
            return agent == Agent.GEMINI ? geminiMatcher : codexMatcher;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private AgentHookConfig() {
    }

    /**
     * The launcher as it must be written in the agent's hook command on this platform. Gemini CLI runs hook commands through
     * PowerShell on Windows, where a quoted program path at the start of a command is a string, not a call: the command
     * must start with the call operator ({@code & "C:/tools/javaguard.exe" hook ...}). Codex does the same with its
     * {@code commandWindows} (found with a real session: "hook exited with code 1"); on other systems its portable
     * {@code command} is the bare program name and takes no prefix. Claude Code runs hooks with Git Bash, and neither
     * Gemini on other systems nor Claude would accept the {@code &}: POSIX shells treat it as a separator.
     */
    public static String launcherFor(Agent agent, String launcher, boolean windows) {
        return (agent == Agent.GEMINI || agent == Agent.CODEX) && windows ? "& " + launcher : launcher;
    }

    /**
     * Whether a Gemini CLI configuration has JavaGuard hooks written without the call operator (by a version of
     * {@code install} that did not know Gemini runs hooks through PowerShell on Windows): they fail there, so JavaGuard
     * never runs. Re-running {@code javaguard install --agent gemini} rewrites them.
     */
    public static boolean hasGeminiCommandWithoutCallOperator(JsonNode settings) {
        return hasWindowsCommandWithoutCallOperator(settings, Agent.GEMINI);
    }

    /**
     * Same check for either agent: Gemini's {@code command}, or Codex's {@code commandWindows}, is the one PowerShell runs
     * on Windows. A Codex entry without a {@code commandWindows} is not affected (its portable command is a bare name).
     */
    public static boolean hasWindowsCommandWithoutCallOperator(JsonNode settings, Agent agent) {
        String field = agent == Agent.CODEX ? "commandWindows" : "command";
        for (Gate gate : Gate.values()) {
            JsonNode groups = settings.path("hooks").path(gate.event(agent));
            if (!groups.isArray()) {
                continue;
            }
            for (JsonNode group : groups) {
                for (JsonNode hook : group.path("hooks")) {
                    if (gateOf(hook, agent).isPresent() && hook.has(field)
                            && !hook.path(field).asText("").stripLeading().startsWith("&")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** {@code <launcher> hook --agent <agent> --gate <gate>}. */
    public static String commandFor(String launcher, Agent agent, Gate gate) {
        return launcher + " hook --agent " + agent.cliName + " --gate " + gate.cliName;
    }

    /**
     * Returns {@code settings} with {@code ensure} present and {@code remove} gone. For Codex on Windows, {@code
     * windowsLauncher} is written as {@code commandWindows} next to a portable {@code command} (the bare program name);
     * pass an empty value elsewhere, where {@code launcher} alone is the command.
     */
    public static ObjectNode install(ObjectNode settings, Agent agent, String launcher, Optional<String> windowsLauncher,
                                     List<Gate> ensure, List<Gate> remove) {
        ObjectNode result = settings.deepCopy();
        for (Gate gate : remove) {
            removeGate(result, agent, gate);
        }
        for (Gate gate : ensure) {
            ensureGate(result, agent, launcher, windowsLauncher, gate);
        }
        return result;
    }

    public static ObjectNode uninstall(ObjectNode settings, Agent agent) {
        ObjectNode result = settings.deepCopy();
        for (Gate gate : Gate.values()) {
            removeGate(result, agent, gate);
        }
        return result;
    }

    public static List<Gate> installedGates(JsonNode settings, Agent agent) {
        List<Gate> found = new ArrayList<>();
        for (Gate gate : Gate.values()) {
            JsonNode groups = settings.path("hooks").path(gate.event(agent));
            if (groups.isArray() && containsGate(groups, agent, gate)) {
                found.add(gate);
            }
        }
        return found;
    }

    /** The gate a hook entry belongs to, if it is one of JavaGuard's for {@code agent}. */
    public static Optional<Gate> gateOf(JsonNode hook, Agent agent) {
        if (!"command".equals(hook.path("type").asText())) {
            return Optional.empty();
        }
        Pattern pattern = Pattern.compile("\\bhook\\s+--agent\\s+" + agent.cliName + "\\s+--gate\\s+(pre-edit|post-edit|end-of-turn|turn-start)\\b");
        for (String field : List.of("command", "commandWindows")) {
            Matcher m = pattern.matcher(hook.path(field).asText(""));
            if (m.find()) {
                for (Gate gate : Gate.values()) {
                    if (gate.cliName.equals(m.group(1))) {
                        return Optional.of(gate);
                    }
                }
            }
        }
        return Optional.empty();
    }

    // --- internals ----------------------------------------------------------------------------

    private static void ensureGate(ObjectNode settings, Agent agent, String launcher, Optional<String> windowsLauncher, Gate gate) {
        ObjectNode hooks = objectChild(settings, "hooks");
        ArrayNode groups = arrayChild(hooks, gate.event(agent));
        boolean found = false;
        for (Iterator<JsonNode> g = groups.elements(); g.hasNext(); ) {
            JsonNode group = g.next();
            JsonNode inner = group.path("hooks");
            if (!inner.isArray()) {
                continue;
            }
            boolean removedDuplicate = false;
            for (Iterator<JsonNode> h = inner.elements(); h.hasNext(); ) {
                JsonNode hook = h.next();
                if (gateOf(hook, agent).filter(gate::equals).isPresent()) {
                    if (found) {
                        h.remove(); // A duplicate from an earlier, interrupted install.
                        removedDuplicate = true;
                    } else {
                        update((ObjectNode) hook, agent, launcher, windowsLauncher, gate);
                        found = true;
                    }
                }
            }
            if (removedDuplicate && inner.isEmpty()) {
                g.remove();
            }
        }
        if (!found) {
            ObjectNode group = JSON.createObjectNode();
            if (gate.matcher(agent) != null) {
                group.put("matcher", gate.matcher(agent));
            }
            ObjectNode hook = group.putArray("hooks").addObject();
            update(hook, agent, launcher, windowsLauncher, gate);
            groups.add(group);
        }
    }

    private static void update(ObjectNode hook, Agent agent, String launcher, Optional<String> windowsLauncher, Gate gate) {
        if (agent == Agent.GEMINI) {
            // Gemini counts timeouts in milliseconds and defaults to 60 s: always explicit (contracts section 4.4).
            hook.put("name", "javaguard-" + gate.cliName);
            hook.put("type", "command");
            hook.put("command", commandFor(launcher, agent, gate));
            hook.put("timeout", gate.timeoutSeconds * 1000);
            return;
        }
        hook.put("type", "command");
        if (windowsLauncher.isPresent()) {
            hook.put("command", commandFor("javaguard", agent, gate));
            hook.put("commandWindows", commandFor(windowsLauncher.get(), agent, gate));
        } else {
            hook.put("command", commandFor(launcher, agent, gate));
            hook.remove("commandWindows");
        }
        hook.put("timeout", gate.timeoutSeconds);
        if (gate.statusMessage != null) {
            hook.put("statusMessage", gate.statusMessage);
        } else {
            hook.remove("statusMessage");
        }
    }

    private static void removeGate(ObjectNode settings, Agent agent, Gate gate) {
        JsonNode hooksNode = settings.get("hooks");
        String event = gate.event(agent);
        if (!(hooksNode instanceof ObjectNode hooks) || !(hooks.get(event) instanceof ArrayNode groups)) {
            return;
        }
        for (Iterator<JsonNode> g = groups.elements(); g.hasNext(); ) {
            JsonNode inner = g.next().path("hooks");
            if (!inner.isArray()) {
                continue;
            }
            boolean hadJavaGuard = false;
            for (Iterator<JsonNode> h = inner.elements(); h.hasNext(); ) {
                if (gateOf(h.next(), agent).filter(gate::equals).isPresent()) {
                    h.remove();
                    hadJavaGuard = true;
                }
            }
            if (hadJavaGuard && inner.isEmpty()) {
                g.remove(); // Only ever drop a group we emptied.
            }
        }
        if (groups.isEmpty()) {
            hooks.remove(event);
        }
        if (hooks.isEmpty()) {
            settings.remove("hooks");
        }
    }

    private static boolean containsGate(JsonNode groups, Agent agent, Gate gate) {
        for (JsonNode group : groups) {
            for (JsonNode hook : group.path("hooks")) {
                if (gateOf(hook, agent).filter(gate::equals).isPresent()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ObjectNode objectChild(ObjectNode parent, String name) {
        JsonNode existing = parent.get(name);
        return existing instanceof ObjectNode object ? object : parent.putObject(name);
    }

    private static ArrayNode arrayChild(ObjectNode parent, String name) {
        JsonNode existing = parent.get(name);
        return existing instanceof ArrayNode array ? array : parent.putArray(name);
    }
}
