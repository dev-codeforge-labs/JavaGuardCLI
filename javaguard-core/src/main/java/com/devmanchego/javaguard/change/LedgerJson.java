package com.devmanchego.javaguard.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ledger &harr; JSON through Jackson's tree model, by hand.
 *
 * <p>Deliberately not record data binding: binding pulls in Jackson's whole
 * bean-introspection machinery (hundreds of classes), which was the largest
 * single cost of a PRE_EDIT hook, whose budget is 300 ms including JVM start
 * (specification &sect;9.1, &sect;20). The payload is already parsed with the
 * tree model, so this reuses classes that are loaded anyway.
 */
final class LedgerJson {

    private LedgerJson() {
    }

    static ObjectNode write(Ledger ledger) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("version", ledger.version());
        root.put("sessionId", ledger.sessionId());
        ObjectNode files = root.putObject("files");
        ledger.files().forEach((path, e) -> {
            ObjectNode n = files.putObject(path);
            n.put("firstTouched", e.firstTouched());
            n.put("baselineSource", e.baselineSource().name());
            n.put("approximate", e.approximate());
            n.put("baselineHash", e.baselineHash());
            n.put("baselineFile", e.baselineFile());
            if (e.lastSeenHash() == null) {
                n.putNull("lastSeenHash");
            } else {
                n.put("lastSeenHash", e.lastSeenHash());
            }
            n.put("deleted", e.deleted());
            ArrayNode runs = n.putArray("gateRuns");
            e.gateRuns().forEach(runs::add);
        });
        return root;
    }

    /** @throws IllegalArgumentException on a structurally invalid ledger */
    static Ledger read(JsonNode root, String sessionId) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("ledger is not a JSON object");
        }
        Map<String, LedgerEntry> files = new LinkedHashMap<>();
        JsonNode filesNode = root.path("files");
        filesNode.properties().forEach(field -> {
            JsonNode n = field.getValue();
            List<String> runs = new ArrayList<>();
            n.path("gateRuns").forEach(r -> runs.add(r.asText()));
            files.put(field.getKey(), new LedgerEntry(
                    required(n, "firstTouched"),
                    BaselineSource.valueOf(required(n, "baselineSource")),
                    n.path("approximate").asBoolean(false),
                    required(n, "baselineHash"),
                    required(n, "baselineFile"),
                    n.hasNonNull("lastSeenHash") ? n.get("lastSeenHash").asText() : null,
                    n.path("deleted").asBoolean(false),
                    runs));
        });
        return new Ledger(root.path("version").asInt(0), root.path("sessionId").asText(sessionId), files);
    }

    private static String required(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isTextual()) {
            throw new IllegalArgumentException("ledger entry lacks '" + field + "'");
        }
        return v.asText();
    }
}
