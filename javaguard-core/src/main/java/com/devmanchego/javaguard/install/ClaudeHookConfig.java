package com.devmanchego.javaguard.install;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure merge logic for Claude Code's {@code settings.json} (contracts &sect;3.4): adds, updates and
 * removes JavaGuard's hook entries and touches nothing else. Foreign hooks, unrelated settings and
 * key order are preserved; running {@link #install} twice yields the same document.
 *
 * <p>A hook entry is recognized as JavaGuard's by its command line
 * ({@code ... hook --agent claude --gate <gate>}), never by position, so a user moving or
 * regrouping entries does not confuse it.
 */
public final class ClaudeHookConfig {

    /** The JavaGuard gates Claude Code can fire (contracts &sect;3.1). */
    public enum Gate {
        PRE_EDIT("PreToolUse", "pre-edit", "Edit|Write|MultiEdit", 10, "JavaGuard: taking a baseline of the file..."),
        POST_EDIT("PostToolUse", "post-edit", "Edit|Write|MultiEdit", 30, "JavaGuard: checking edited Java file..."),
        END_OF_TURN("Stop", "end-of-turn", null, 600, "JavaGuard: compiling and running affected tests..."),
        /** Records the working tree when a turn begins; silent, so no status message. */
        TURN_START("UserPromptSubmit", "turn-start", null, 10, null);

        private final String event;
        private final String cliName;
        private final String matcher;
        private final int timeoutSeconds;
        private final String statusMessage;

        Gate(String event, String cliName, String matcher, int timeoutSeconds, String statusMessage) {
            this.event = event;
            this.cliName = cliName;
            this.matcher = matcher;
            this.timeoutSeconds = timeoutSeconds;
            this.statusMessage = statusMessage;
        }

        public String event() {
            return event;
        }

        public String cliName() {
            return cliName;
        }
    }

    private static final Pattern JAVAGUARD_COMMAND =
            Pattern.compile("\\bhook\\s+--agent\\s+claude\\s+--gate\\s+(pre-edit|post-edit|end-of-turn|turn-start)\\b");
    private static final ObjectMapper JSON = new ObjectMapper();

    private ClaudeHookConfig() {
    }

    /** The shell command for {@code gate}, given the launcher prefix (already quoted). */
    public static String commandFor(String launcher, Gate gate) {
        return launcher + " hook --agent claude --gate " + gate.cliName;
    }

    /** Quotes an executable path for the hook command line, with forward slashes (contracts &sect;3.4). */
    public static String quoteExecutable(String path) {
        return "\"" + path.replace('\\', '/') + "\"";
    }

    /**
     * Returns {@code settings} with the given gates ensured and the other JavaGuard gates removed
     * from {@code remove}. The input is not modified.
     *
     * @param launcher quoted launcher prefix, e.g. {@code "C:/tools/javaguard/javaguard.exe"}
     * @param ensure   gates that must be present afterwards
     * @param remove   gates whose JavaGuard entries must be gone afterwards
     */
    public static ObjectNode install(ObjectNode settings, String launcher, List<Gate> ensure, List<Gate> remove) {
        ObjectNode result = settings.deepCopy();
        for (Gate gate : remove) {
            removeGate(result, gate);
        }
        for (Gate gate : ensure) {
            ensureGate(result, launcher, gate);
        }
        return result;
    }

    /** Returns {@code settings} without any JavaGuard hook entry; emptied containers are dropped. */
    public static ObjectNode uninstall(ObjectNode settings) {
        ObjectNode result = settings.deepCopy();
        for (Gate gate : Gate.values()) {
            removeGate(result, gate);
        }
        return result;
    }

    /** The JavaGuard gates present in {@code settings}. */
    public static List<Gate> installedGates(JsonNode settings) {
        List<Gate> found = new ArrayList<>();
        for (Gate gate : Gate.values()) {
            JsonNode groups = settings.path("hooks").path(gate.event);
            if (groups.isArray() && containsGate(groups, gate)) {
                found.add(gate);
            }
        }
        return found;
    }

    /** The gate a hook entry belongs to, if the entry is one of JavaGuard's. */
    public static java.util.Optional<Gate> gateOf(JsonNode hook) {
        if (!"command".equals(hook.path("type").asText())) {
            return java.util.Optional.empty();
        }
        Matcher m = JAVAGUARD_COMMAND.matcher(hook.path("command").asText(""));
        if (!m.find()) {
            return java.util.Optional.empty();
        }
        for (Gate gate : Gate.values()) {
            if (gate.cliName.equals(m.group(1))) {
                return java.util.Optional.of(gate);
            }
        }
        return java.util.Optional.empty();
    }

    /** Pretty JSON in the style Claude Code's own tooling writes: two-space indent, {@code "key": value}. */
    public static String render(JsonNode settings) {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withSeparators(Separators.createDefaultInstance()
                        .withObjectFieldValueSpacing(Separators.Spacing.AFTER));
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        try {
            return JSON.writer(printer).writeValueAsString(settings) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize settings", e);
        }
    }

    // --- internals ----------------------------------------------------------------------------

    private static void ensureGate(ObjectNode settings, String launcher, Gate gate) {
        ObjectNode hooks = objectChild(settings, "hooks");
        ArrayNode groups = arrayChild(hooks, gate.event);
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
                if (gateOf(hook).filter(gate::equals).isPresent()) {
                    if (found) {
                        h.remove(); // A duplicate from an earlier, interrupted install.
                        removedDuplicate = true;
                    } else {
                        update((ObjectNode) hook, launcher, gate);
                        found = true;
                    }
                }
            }
            if (removedDuplicate && inner.isEmpty()) {
                g.remove(); // Only ever drop a group this method emptied.
            }
        }
        if (!found) {
            ObjectNode group = JSON.createObjectNode();
            if (gate.matcher != null) {
                group.put("matcher", gate.matcher);
            }
            ArrayNode inner = group.putArray("hooks");
            ObjectNode hook = inner.addObject();
            update(hook, launcher, gate);
            groups.add(group);
        }
    }

    private static void update(ObjectNode hook, String launcher, Gate gate) {
        hook.put("type", "command");
        hook.put("command", commandFor(launcher, gate));
        hook.put("timeout", gate.timeoutSeconds);
        if (gate.statusMessage != null) {
            hook.put("statusMessage", gate.statusMessage);
        } else {
            hook.remove("statusMessage");
        }
    }

    private static void removeGate(ObjectNode settings, Gate gate) {
        JsonNode hooksNode = settings.get("hooks");
        if (!(hooksNode instanceof ObjectNode hooks) || !(hooks.get(gate.event) instanceof ArrayNode groups)) {
            return;
        }
        for (Iterator<JsonNode> g = groups.elements(); g.hasNext(); ) {
            JsonNode group = g.next();
            JsonNode inner = group.path("hooks");
            if (!inner.isArray()) {
                continue;
            }
            boolean hadJavaGuard = false;
            for (Iterator<JsonNode> h = inner.elements(); h.hasNext(); ) {
                if (gateOf(h.next()).filter(gate::equals).isPresent()) {
                    h.remove();
                    hadJavaGuard = true;
                }
            }
            if (hadJavaGuard && inner.isEmpty()) {
                g.remove(); // Only ever drop a group we emptied; foreign empty groups stay as they were.
            }
        }
        if (groups.isEmpty()) {
            hooks.remove(gate.event);
        }
        if (hooks.isEmpty()) {
            settings.remove("hooks");
        }
    }

    private static boolean containsGate(JsonNode groups, Gate gate) {
        for (JsonNode group : groups) {
            for (JsonNode hook : group.path("hooks")) {
                if (gateOf(hook).filter(gate::equals).isPresent()) {
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
