package com.devmanchego.javaguard.report;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Fingerprint;
import com.devmanchego.javaguard.model.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates {@link SarifReportWriter} output against the real, official
 * SARIF 2.1.0 JSON schema (plan Phase 3: "SARIF validated against the 2.1.0
 * JSON schema"). The schema file is a genuine third-party artifact
 * (src/test/resources/schema/sarif-schema-2.1.0.json, fetched from
 * json.schemastore.org, which mirrors the OASIS specification), not something
 * authored for this project.
 */
class SarifReportWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonSchema schema() {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().build();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        try (InputStream in = SarifReportWriterTest.class.getClassLoader()
                .getResourceAsStream("schema/sarif-schema-2.1.0.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource schema/sarif-schema-2.1.0.json");
            }
            return factory.getSchema(MAPPER.readTree(in), config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Finding finding(String ruleId, String title, Category category, Severity severity,
                                   Optional<Path> file, int line, int column, int endLine) {
        return new Finding(ruleId, title, category, severity, "ast", file, line, column, endLine, "message",
                "remediation", Optional.empty(), Map.of(), new Fingerprint(Integer.toHexString(ruleId.hashCode() & 0xFFFFFFF)));
    }

    @Test
    void aRealisticSetOfFindingsValidatesAgainstTheOfficialSchema() {
        JsonNode sarif = SarifReportWriter.toSarif(java.util.List.of(
                finding("JG-SEC-001", "SQL built by concatenation", Category.SECURITY, Severity.BLOCKER,
                        Optional.of(Path.of("src/main/java/com/acme/UserDao.java")), 42, 5, 42),
                finding("JG-PERF-001", "String concatenation in loop", Category.PERFORMANCE, Severity.MAJOR,
                        Optional.of(Path.of("src/main/java/com/acme/ReportBuilder.java")), 118, 0, 118),
                finding("JG-AI-001", "Console output in production code", Category.AI_DEBRIS, Severity.INFO,
                        Optional.of(Path.of("src/main/java/com/acme/Main.java")), 10, 0, 10),
                // A finding with no file/line at all must still produce schema-valid SARIF.
                finding("JG-DEP-003", "Dependency cannot be resolved", Category.DEPENDENCY, Severity.BLOCKER,
                        Optional.empty(), 0, 0, 0)));

        Set<ValidationMessage> errors = schema().validate(sarif);

        assertThat(errors).as("schema validation errors: %s", errors).isEmpty();
    }

    @Test
    void emptyFindingsListIsStillValidSarif() {
        Set<ValidationMessage> errors = schema().validate(SarifReportWriter.toSarif(java.util.List.of()));

        assertThat(errors).isEmpty();
    }

    @Test
    void topLevelShapeMatchesSarif210() {
        JsonNode sarif = SarifReportWriter.toSarif(java.util.List.of(
                finding("JG-X", "t", Category.RELIABILITY, Severity.MAJOR, Optional.of(Path.of("A.java")), 1, 1, 1)));

        assertThat(sarif.path("version").asText()).isEqualTo("2.1.0");
        assertThat(sarif.path("runs")).hasSize(1);
        assertThat(sarif.path("runs").get(0).path("tool").path("driver").path("name").asText()).isEqualTo("JavaGuardCLI");
    }

    @Test
    void severityMapsToTheThreeSarifLevels() {
        JsonNode sarif = SarifReportWriter.toSarif(java.util.List.of(
                finding("A", "a", Category.SECURITY, Severity.BLOCKER, Optional.of(Path.of("F.java")), 1, 1, 1),
                finding("B", "b", Category.SECURITY, Severity.CRITICAL, Optional.of(Path.of("F.java")), 2, 1, 2),
                finding("C", "c", Category.SECURITY, Severity.MAJOR, Optional.of(Path.of("F.java")), 3, 1, 3),
                finding("D", "d", Category.SECURITY, Severity.INFO, Optional.of(Path.of("F.java")), 4, 1, 4)));
        JsonNode results = sarif.path("runs").get(0).path("results");

        assertThat(results.get(0).path("level").asText()).isEqualTo("error");
        assertThat(results.get(1).path("level").asText()).isEqualTo("error");
        assertThat(results.get(2).path("level").asText()).isEqualTo("warning");
        assertThat(results.get(3).path("level").asText()).isEqualTo("note");
    }

    @Test
    void rulesArrayListsEachDistinctRuleOnce() {
        JsonNode sarif = SarifReportWriter.toSarif(java.util.List.of(
                finding("JG-X", "t", Category.RELIABILITY, Severity.MAJOR, Optional.of(Path.of("A.java")), 1, 1, 1),
                finding("JG-X", "t", Category.RELIABILITY, Severity.MAJOR, Optional.of(Path.of("B.java")), 2, 1, 2)));

        assertThat(sarif.path("runs").get(0).path("tool").path("driver").path("rules")).hasSize(1);
    }

    @Test
    void pathsInLocationsUseForwardSlashes() {
        JsonNode sarif = SarifReportWriter.toSarif(java.util.List.of(
                finding("JG-X", "t", Category.RELIABILITY, Severity.MAJOR, Optional.of(Path.of("src", "main", "A.java")), 1, 1, 1)));

        String uri = sarif.path("runs").get(0).path("results").get(0)
                .path("locations").get(0).path("physicalLocation").path("artifactLocation").path("uri").asText();
        assertThat(uri).isEqualTo("src/main/A.java");
    }
}
