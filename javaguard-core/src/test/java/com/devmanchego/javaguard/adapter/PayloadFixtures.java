package com.devmanchego.javaguard.adapter;

import com.devmanchego.javaguard.model.EditFragment;
import com.devmanchego.javaguard.model.FileChange;
import com.devmanchego.javaguard.model.HookEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Loads payload fixtures from {@code src/test/resources/payloads/<agent>/}
 * and converts {@link HookEvent}s to comparable JSON (see payloads/README.md).
 */
final class PayloadFixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROJECT = "${PROJECT}";

    private PayloadFixtures() {
    }

    /** Fixture names (without {@code .json}) for an agent directory. */
    static List<String> names(String agentDir) {
        try (Stream<Path> files = Files.list(resourceDir("payloads/" + agentDir))) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json") && !n.endsWith(".expected.json"))
                    .map(n -> n.substring(0, n.length() - ".json".length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The payload with {@code ${PROJECT}} and {@code ${SEP}} substituted. */
    static JsonNode payload(String agentDir, String name, Path projectDir) {
        String raw = read("payloads/" + agentDir + "/" + name + ".json");
        String substituted = raw
                .replace(PROJECT, jsonEscape(projectDir.toString()))
                .replace("${SEP}", jsonEscape(projectDir.getFileSystem().getSeparator()));
        return readTree(substituted);
    }

    static byte[] payloadBytes(String agentDir, String name, Path projectDir) {
        try {
            return MAPPER.writeValueAsBytes(payload(agentDir, name, projectDir));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode expected(String agentDir, String name) {
        return readTree(read("payloads/" + agentDir + "/" + name + ".expected.json"));
    }

    /** Comparable JSON view of an event: paths rewritten relative to {@code ${PROJECT}} with '/'. */
    static JsonNode toJson(HookEvent event, Path projectDir) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("agent", event.agent().name());
        node.put("gate", event.gate().name());
        node.put("sessionId", event.sessionId());
        node.put("cwd", portable(event.cwd(), projectDir));
        if (event.transcriptPath().isPresent()) {
            node.put("transcriptPath", portable(event.transcriptPath().get(), projectDir));
        } else {
            node.putNull("transcriptPath");
        }
        node.put("toolName", event.toolName());
        ArrayNode changes = node.putArray("changes");
        for (FileChange change : event.changes()) {
            ObjectNode c = changes.addObject();
            c.put("path", portable(change.path(), projectDir));
            c.put("type", change.type().name());
            if (change.movedFrom().isPresent()) {
                c.put("movedFrom", portable(change.movedFrom().get(), projectDir));
            } else {
                c.putNull("movedFrom");
            }
            ArrayNode fragments = c.putArray("fragments");
            for (EditFragment f : change.fragments()) {
                ObjectNode fn = fragments.addObject();
                fn.put("oldText", f.oldText());
                fn.put("newText", f.newText());
                fn.put("replaceAll", f.replaceAll());
            }
        }
        node.put("stopHookActive", event.stopHookActive());
        if (event.lastAssistantMessage().isPresent()) {
            node.put("lastAssistantMessage", event.lastAssistantMessage().get());
        } else {
            node.putNull("lastAssistantMessage");
        }
        return node;
    }

    static String portable(Path path, Path projectDir) {
        String actual = path.toString().replace('\\', '/');
        String project = projectDir.toString().replace('\\', '/');
        if (actual.equals(project)) {
            return PROJECT;
        }
        if (actual.startsWith(project + "/")) {
            return PROJECT + actual.substring(project.length());
        }
        return actual;
    }

    static String read(String resource) {
        try {
            return Files.readString(resourcePath(resource), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Path resourcePath(String resource) {
        URL url = Objects.requireNonNull(PayloadFixtures.class.getClassLoader().getResource(resource),
                () -> "missing test resource " + resource);
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path resourceDir(String resource) {
        return resourcePath(resource);
    }

    private static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String jsonEscape(String value) {
        try {
            String quoted = MAPPER.writeValueAsString(value);
            return quoted.substring(1, quoted.length() - 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
