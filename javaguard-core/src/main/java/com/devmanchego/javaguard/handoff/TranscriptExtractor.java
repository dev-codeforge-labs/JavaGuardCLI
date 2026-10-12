package com.devmanchego.javaguard.handoff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The agent's own words before it got stuck (specification &sect;14.2 section 6): the last assistant messages of its
 * transcript, tool calls reduced to one line each. The three agents write different files - Claude Code a JSONL of
 * {@code {type:"assistant", message:{content:[{type:"text"|"tool_use",...}]}}}, Codex a JSONL of
 * {@code {payload:{type:"message", role:"assistant", content:[{type:"output_text"}]}}} and
 * {@code {payload:{type:"function_call"}}}, Gemini CLI a JSON document with {@code messages[]} of
 * {@code type:"gemini"} - and all of them change between versions, so extraction is by shape, not by agent: any
 * node whose {@code role}/{@code type} says assistant/model/gemini is a message, its text comes from whichever of
 * {@code content}, {@code parts} or {@code text} it has, and tool calls from {@code tool_use}, {@code function_call}
 * or {@code toolCalls}. Anything unreadable yields no messages and a note, never an exception.
 */
public final class TranscriptExtractor {

    /** The excerpts, oldest first, and why there are none when there are none. */
    public record Excerpts(List<String> messages, Optional<String> note) {
        public Excerpts {
            messages = List.copyOf(messages);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ASSISTANT_MARKERS = Set.of("assistant", "model", "gemini", "agent");
    private static final int MAX_MESSAGE_CHARS = 2000;
    private static final int MAX_TOOL_ARGUMENT_CHARS = 120;

    private TranscriptExtractor() {
    }

    public static Excerpts lastAssistantMessages(Optional<Path> transcript, int count) {
        if (transcript.isEmpty()) {
            return new Excerpts(List.of(), Optional.of("The agent did not report a transcript path."));
        }
        Path file = transcript.get();
        if (!Files.isRegularFile(file)) {
            return new Excerpts(List.of(), Optional.of("Transcript not found: " + file.getFileName() + "."));
        }
        List<String> messages = new ArrayList<>();
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            collect(text, messages);
        } catch (IOException | RuntimeException e) {
            return new Excerpts(List.of(), Optional.of("Transcript unreadable: " + e.getMessage() + "."));
        }
        if (messages.isEmpty()) {
            return new Excerpts(List.of(), Optional.of("No assistant messages recognized in the transcript."));
        }
        return new Excerpts(messages.subList(Math.max(0, messages.size() - Math.max(1, count)), messages.size()), Optional.empty());
    }

    private static void collect(String text, List<String> out) throws IOException {
        String stripped = text.stripLeading();
        if (stripped.startsWith("[") || (stripped.startsWith("{") && isSingleDocument(stripped))) {
            visit(JSON.readTree(stripped), out);
            return;
        }
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                visit(JSON.readTree(line), out);
            } catch (IOException ignored) {
                // A torn or foreign line must not hide the rest.
            }
        }
    }

    /** A pretty-printed or one-line JSON document, as opposed to a JSONL stream of objects. */
    private static boolean isSingleDocument(String text) {
        try (var parser = JSON.getFactory().createParser(text)) {
            parser.nextToken();
            parser.skipChildren();
            return parser.nextToken() == null;
        } catch (IOException e) {
            return false;
        }
    }

    private static void visit(JsonNode node, List<String> out) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> visit(child, out));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        if (node.has("message") && node.get("message").isObject()) {
            visit(node.get("message"), out);
            return;
        }
        if (node.has("payload") && node.get("payload").isObject()) {
            visit(node.get("payload"), out);
            return;
        }
        if (node.has("messages") && node.get("messages").isArray()) {
            visit(node.get("messages"), out);
            return;
        }
        String type = text(node, "type");
        if (type.equals("function_call") || type.equals("tool_use")) {
            out.add(toolSummary(text(node, "name"), node.has("arguments") ? node.get("arguments") : node.get("input")));
            return;
        }
        String who = !text(node, "role").isEmpty() ? text(node, "role") : type;
        if (!ASSISTANT_MARKERS.contains(who.toLowerCase())) {
            return;
        }
        StringBuilder message = new StringBuilder();
        appendContent(node.get("content"), message);
        appendContent(node.get("parts"), message);
        if (node.has("text") && node.get("text").isTextual()) {
            appendLine(message, node.get("text").asText());
        }
        if (node.has("toolCalls") && node.get("toolCalls").isArray()) {
            node.get("toolCalls").forEach(call -> appendLine(message,
                    toolSummary(text(call, "name"), call.has("args") ? call.get("args") : call.get("arguments"))));
        }
        String joined = message.toString().strip();
        if (!joined.isEmpty()) {
            out.add(joined.length() > MAX_MESSAGE_CHARS ? joined.substring(0, MAX_MESSAGE_CHARS) + " (...)" : joined);
        }
    }

    private static void appendContent(JsonNode content, StringBuilder message) {
        if (content == null || content.isNull()) {
            return;
        }
        if (content.isTextual()) {
            appendLine(message, content.asText());
        } else if (content.isArray()) {
            for (JsonNode block : content) {
                if (block.isTextual()) {
                    appendLine(message, block.asText());
                } else if (block.has("text") && block.get("text").isTextual()) {
                    appendLine(message, block.get("text").asText());
                } else if ("tool_use".equals(text(block, "type")) || "function_call".equals(text(block, "type"))) {
                    appendLine(message, toolSummary(text(block, "name"), block.has("input") ? block.get("input") : block.get("arguments")));
                } else if (block.has("functionCall")) {
                    appendLine(message, toolSummary(text(block.get("functionCall"), "name"), block.get("functionCall").get("args")));
                }
            }
        }
    }

    private static String toolSummary(String name, JsonNode arguments) {
        String detail = "";
        if (arguments != null && !arguments.isNull()) {
            JsonNode parsed = arguments;
            if (arguments.isTextual()) {
                try {
                    parsed = JSON.readTree(arguments.asText());
                } catch (IOException e) {
                    parsed = arguments;
                }
            }
            for (String key : List.of("file_path", "path", "command", "pattern", "query")) {
                if (parsed.has(key) && parsed.get(key).isTextual()) {
                    detail = parsed.get(key).asText();
                    break;
                }
            }
            if (detail.isEmpty() && parsed.isTextual()) {
                detail = parsed.asText();
            }
        }
        detail = detail.replaceAll("\\s+", " ").strip();
        if (detail.length() > MAX_TOOL_ARGUMENT_CHARS) {
            detail = detail.substring(0, MAX_TOOL_ARGUMENT_CHARS) + "...";
        }
        return "[tool call: " + (name.isEmpty() ? "?" : name) + (detail.isEmpty() ? "" : " " + detail) + "]";
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (!line.isBlank()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line.strip());
        }
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.has(field) && node.get(field).isTextual() ? node.get(field).asText() : "";
    }
}
