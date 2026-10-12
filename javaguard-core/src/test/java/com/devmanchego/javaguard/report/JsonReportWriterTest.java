package com.devmanchego.javaguard.report;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Escalation;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Fingerprint;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.model.Suppression;
import com.devmanchego.javaguard.model.Verdict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JsonReportWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void roundTripsEveryFindingField() throws Exception {
        Finding f = new Finding("JG-SEC-001", "t", Category.SECURITY, Severity.BLOCKER, "ast",
                Optional.of(Path.of("src", "A.java")), 42, 5, 43, "msg", "fix", Optional.of("snippet"),
                Map.of("trace", "x"), new Fingerprint("abc123"));
        GateResult r = new GateResult(GateKind.END_OF_TURN, Verdict.BLOCK, List.of(f), List.of(), List.of("note"),
                Optional.of(new Escalation(2, "repeat", 3)), Optional.of(Path.of(".javaguard", "reports", "x.md")),
                List.of(new Suppression("JG-X", Path.of("A.java"), 1, Optional.of("fp"))), Duration.ofMillis(500));

        JsonNode json = MAPPER.readTree(JsonReportWriter.write(r));

        assertThat(json.path("gate").asText()).isEqualTo("end-of-turn");
        assertThat(json.path("verdict").asText()).isEqualTo("BLOCK");
        assertThat(json.path("elapsedMillis").asLong()).isEqualTo(500);
        JsonNode blocking0 = json.path("blocking").get(0);
        assertThat(blocking0.path("ruleId").asText()).isEqualTo("JG-SEC-001");
        assertThat(blocking0.path("file").asText()).isEqualTo("src/A.java");
        assertThat(blocking0.path("line").asInt()).isEqualTo(42);
        assertThat(blocking0.path("detail").path("trace").asText()).isEqualTo("x");
        assertThat(blocking0.path("fingerprint").asText()).isEqualTo("abc123");
        assertThat(json.path("notes").get(0).asText()).isEqualTo("note");
        assertThat(json.path("escalation").path("detector").asText()).isEqualTo("repeat");
        assertThat(json.path("fullReport").asText()).isEqualTo(".javaguard/reports/x.md");
        assertThat(json.path("newSuppressions").get(0).path("reason").asText()).isEqualTo("fp");
    }

    @Test
    void absentOptionalsBecomeExplicitNulls() throws Exception {
        GateResult r = new GateResult(GateKind.POST_EDIT, Verdict.PASS, List.of(), List.of(), List.of(),
                Optional.empty(), Optional.empty(), List.of(), Duration.ZERO);

        JsonNode json = MAPPER.readTree(JsonReportWriter.write(r));

        assertThat(json.path("escalation").isNull()).isTrue();
        assertThat(json.path("fullReport").isNull()).isTrue();
    }

    @Test
    void findingWithoutAFileHasExplicitNullFile() throws Exception {
        Finding f = new Finding("TEST", "t#m", Category.TEST_FAILURE, Severity.CRITICAL, "surefire", Optional.empty(),
                0, 0, 0, "msg", "fix", Optional.empty(), Map.of(), new Fingerprint("a1"));
        GateResult r = new GateResult(GateKind.END_OF_TURN, Verdict.BLOCK, List.of(f), List.of(), List.of(),
                Optional.empty(), Optional.empty(), List.of(), Duration.ZERO);

        JsonNode json = MAPPER.readTree(JsonReportWriter.write(r));

        assertThat(json.path("blocking").get(0).path("file").isNull()).isTrue();
    }
}
