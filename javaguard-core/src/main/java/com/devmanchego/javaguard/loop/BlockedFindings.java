package com.devmanchego.javaguard.loop;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Fingerprint;
import com.devmanchego.javaguard.model.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The blocking findings of a session's latest blocked run (state {@code blocking.json}), kept so that a handoff can be
 * produced later, on demand, without re-running the gate. Written by {@link LoopGuard} under the session lock.
 */
public final class BlockedFindings {

    /** The gate that blocked and what it found. */
    public record Snapshot(String gate, List<Finding> findings) {
        public Snapshot {
            findings = List.copyOf(findings);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private BlockedFindings() {
    }

    static void write(Path file, String gate, List<Finding> findings) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("gate", gate);
        ArrayNode array = root.putArray("findings");
        for (Finding f : findings) {
            ObjectNode n = array.addObject();
            n.put("ruleId", f.ruleId());
            n.put("title", f.title());
            n.put("category", f.category().name());
            n.put("severity", f.severity().name());
            n.put("engine", f.engine());
            f.file().ifPresent(p -> n.put("file", p.toString().replace('\\', '/')));
            n.put("line", f.line());
            n.put("column", f.column());
            n.put("endLine", f.endLine());
            n.put("message", f.message());
            n.put("remediation", f.remediation());
            f.snippet().ifPresent(s -> n.put("snippet", s));
            ObjectNode detail = n.putObject("detail");
            f.detail().forEach(detail::put);
            n.put("fingerprint", f.fingerprint().value());
        }
        Files.write(file, JSON.writeValueAsBytes(root));
    }

    public static Optional<Snapshot> read(Path sessionDir) {
        Path file = sessionDir.resolve("blocking.json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(file.toFile());
            List<Finding> findings = new ArrayList<>();
            for (JsonNode n : root.path("findings")) {
                Map<String, String> detail = new LinkedHashMap<>();
                n.path("detail").fields().forEachRemaining(e -> detail.put(e.getKey(), e.getValue().asText()));
                findings.add(new Finding(n.path("ruleId").asText(), n.path("title").asText(),
                        Category.valueOf(n.path("category").asText()), Severity.valueOf(n.path("severity").asText()),
                        n.path("engine").asText(), n.has("file") ? Optional.of(Path.of(n.get("file").asText())) : Optional.empty(),
                        n.path("line").asInt(), n.path("column").asInt(), n.path("endLine").asInt(), n.path("message").asText(),
                        n.path("remediation").asText(), n.has("snippet") ? Optional.of(n.get("snippet").asText()) : Optional.empty(),
                        detail, new Fingerprint(n.path("fingerprint").asText())));
            }
            return Optional.of(new Snapshot(root.path("gate").asText(), findings));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
