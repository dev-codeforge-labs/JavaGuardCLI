package com.devmanchego.javaguard.report;

import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SARIF 2.1.0 writer (specification &sect;4, &sect;12.3, {@code report.sarif}):
 * for IDEs and CI, never for the agent (contracts, decision D6 - SARIF is too
 * verbose and deeply nested for a model to consume efficiently).
 *
 * <p>Built directly as a JSON tree rather than through a SARIF library,
 * since only a small, fixed slice of the format is needed; validated in
 * tests against the real SARIF 2.1.0 JSON schema.
 */
public final class SarifReportWriter {

    private static final String SCHEMA_URI = "https://json.schemastore.org/sarif-2.1.0.json";
    private static final String TOOL_NAME = "JavaGuardCLI";
    private static final String INFORMATION_URI = "https://github.com/devmanchego/javaguard";
    /** Bumped independently of the product version until Phase 19 wires in the real one. */
    private static final String TOOL_VERSION = "1.0.0";

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private SarifReportWriter() {
    }

    public static String write(List<Finding> findings) {
        try {
            return MAPPER.writeValueAsString(toSarif(findings));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize findings to SARIF", e);
        }
    }

    public static ObjectNode toSarif(List<Finding> findings) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("$schema", SCHEMA_URI);
        root.put("version", "2.1.0");
        ArrayNode runs = root.putArray("runs");
        ObjectNode run = runs.addObject();

        ObjectNode driver = run.putObject("tool").putObject("driver");
        driver.put("name", TOOL_NAME);
        driver.put("informationUri", INFORMATION_URI);
        driver.put("version", TOOL_VERSION);
        ArrayNode rules = driver.putArray("rules");

        ArrayNode results = run.putArray("results");
        Map<String, String> ruleDescriptions = new LinkedHashMap<>();
        for (Finding f : findings) {
            ruleDescriptions.putIfAbsent(f.ruleId(), f.title());
            addResult(results, f);
        }
        ruleDescriptions.forEach((ruleId, title) -> {
            ObjectNode rule = rules.addObject();
            rule.put("id", ruleId);
            rule.putObject("shortDescription").put("text", title.isBlank() ? ruleId : title);
        });
        return root;
    }

    private static void addResult(ArrayNode results, Finding f) {
        ObjectNode result = results.addObject();
        result.put("ruleId", f.ruleId());
        result.put("level", sarifLevel(f.severity()));
        result.putObject("message").put("text", f.message().isBlank() ? f.title() : f.message());
        f.file().ifPresent(file -> {
            ArrayNode locations = result.putArray("locations");
            ObjectNode physical = locations.addObject().putObject("physicalLocation");
            physical.putObject("artifactLocation").put("uri", slash(file));
            if (f.line() > 0) {
                ObjectNode region = physical.putObject("region");
                region.put("startLine", f.line());
                if (f.column() > 0) {
                    region.put("startColumn", f.column());
                }
                if (f.endLine() > 0 && f.endLine() != f.line()) {
                    region.put("endLine", f.endLine());
                }
            }
        });
    }

    /** SARIF's three severities are coarser than ours; BLOCKER/CRITICAL both mean "error". */
    private static String sarifLevel(Severity severity) {
        return switch (severity) {
            case BLOCKER, CRITICAL -> "error";
            case MAJOR -> "warning";
            case INFO -> "note";
        };
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }
}
