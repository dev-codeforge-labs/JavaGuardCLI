package com.devmanchego.javaguard.report;

import com.devmanchego.javaguard.model.Escalation;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.Suppression;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.Map;

/**
 * {@code javaguard check --format json} (specification &sect;4, &sect;12.3):
 * the full {@link GateResult}, written by hand rather than through automatic
 * bean serialization so the field names and the handling of {@link Path} and
 * {@link java.util.Optional} are exact and stable, independent of how the
 * domain records happen to be shaped.
 */
public final class JsonReportWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private JsonReportWriter() {
    }

    public static String write(GateResult result) {
        try {
            return MAPPER.writeValueAsString(toJson(result));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize GateResult to JSON", e);
        }
    }

    public static ObjectNode toJson(GateResult result) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("gate", result.gate().cliName());
        root.put("verdict", result.verdict().name());
        root.put("elapsedMillis", result.elapsed().toMillis());
        putFindings(root.putArray("blocking"), result.blocking());
        putFindings(root.putArray("advisory"), result.advisory());
        ArrayNode notes = root.putArray("notes");
        result.notes().forEach(notes::add);
        ArrayNode suppressions = root.putArray("newSuppressions");
        for (Suppression s : result.newSuppressions()) {
            ObjectNode node = suppressions.addObject();
            node.put("ruleId", s.ruleId());
            node.put("file", slash(s.file()));
            node.put("line", s.line());
            s.reason().ifPresentOrElse(r -> node.put("reason", r), () -> node.putNull("reason"));
        }
        result.fullReport().ifPresentOrElse(p -> root.put("fullReport", slash(p)), () -> root.putNull("fullReport"));
        result.escalation().ifPresentOrElse(e -> putEscalation(root.putObject("escalation"), e),
                () -> root.putNull("escalation"));
        return root;
    }

    private static void putFindings(ArrayNode array, java.util.List<Finding> findings) {
        for (Finding f : findings) {
            ObjectNode node = array.addObject();
            node.put("ruleId", f.ruleId());
            node.put("title", f.title());
            node.put("category", f.category().name());
            node.put("severity", f.severity().name());
            node.put("engine", f.engine());
            f.file().ifPresentOrElse(p -> node.put("file", slash(p)), () -> node.putNull("file"));
            node.put("line", f.line());
            node.put("column", f.column());
            node.put("endLine", f.endLine());
            node.put("message", f.message());
            node.put("remediation", f.remediation());
            f.snippet().ifPresentOrElse(s -> node.put("snippet", s), () -> node.putNull("snippet"));
            ObjectNode detail = node.putObject("detail");
            for (Map.Entry<String, String> e : f.detail().entrySet()) {
                detail.put(e.getKey(), e.getValue());
            }
            node.put("fingerprint", f.fingerprint().value());
        }
    }

    private static void putEscalation(ObjectNode node, Escalation e) {
        node.put("level", e.level());
        node.put("detector", e.detector());
        node.put("attempts", e.attempts());
        node.put("guidance", e.guidance());
        node.put("handoff", e.handoff());
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }
}
