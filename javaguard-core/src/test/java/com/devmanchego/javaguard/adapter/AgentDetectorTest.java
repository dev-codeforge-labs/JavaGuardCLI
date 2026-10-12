package com.devmanchego.javaguard.adapter;

import com.devmanchego.javaguard.model.AgentKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Detection edge cases beyond the fixtures (which are all covered by
 * {@link AdapterParseGoldenTest#autoDetectionIdentifiesTheFixturesAgent}).
 */
class AgentDetectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"hook_event_name\":\"AfterTool\"}                                          | GEMINI",
            "{\"hook_event_name\":\"BeforeTool\"}                                         | GEMINI",
            "{\"hook_event_name\":\"AfterAgent\"}                                         | GEMINI",
            "{\"hook_event_name\":\"PostToolUse\",\"tool_name\":\"apply_patch\"}          | CODEX",
            "{\"hook_event_name\":\"Stop\",\"turn_id\":\"t1\"}                            | CODEX",
            "{\"hook_event_name\":\"PostToolUse\",\"model\":\"gpt\",\"tool_name\":\"Bash\"} | CODEX",
            "{\"hook_event_name\":\"PostToolUse\",\"tool_use_id\":\"x\",\"tool_name\":\"Edit\"} | CLAUDE",
            "{\"hook_event_name\":\"Stop\"}                                               | CLAUDE",
            "{\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"Write\"}                 | CLAUDE",
    })
    void detectsFromPayloadAlone(String payload, AgentKind expected) throws Exception {
        assertThat(AgentDetector.detect(json(payload), Map.of())).contains(expected);
    }

    @Test
    void unknownEventWithoutEnvironmentIsUndetermined() throws Exception {
        assertThat(AgentDetector.detect(json("{\"hook_event_name\":\"Notification\"}"), Map.of())).isEmpty();
    }

    @Test
    void environmentBreaksTiesForUnknownEvents() throws Exception {
        JsonNode payload = json("{\"hook_event_name\":\"Notification\"}");

        assertThat(AgentDetector.detect(payload, Map.of("GEMINI_PROJECT_DIR", "/p"))).contains(AgentKind.GEMINI);
        assertThat(AgentDetector.detect(payload, Map.of("CLAUDE_PROJECT_DIR", "/p"))).contains(AgentKind.CLAUDE);
    }

    @Test
    void payloadSignalsWinOverEnvironment() throws Exception {
        // A Claude-launched shell could leak CLAUDE_PROJECT_DIR into a Codex run.
        JsonNode codex = json("{\"hook_event_name\":\"Stop\",\"turn_id\":\"t\"}");

        assertThat(AgentDetector.detect(codex, Map.of("CLAUDE_PROJECT_DIR", "/p"))).contains(AgentKind.CODEX);
    }

    @Test
    void missingEventNameIsUndeterminedEvenWithEnvironmentWhenNothingElseMatches() throws Exception {
        assertThat(AgentDetector.detect(json("{}"), Map.of())).isEmpty();
    }
}
