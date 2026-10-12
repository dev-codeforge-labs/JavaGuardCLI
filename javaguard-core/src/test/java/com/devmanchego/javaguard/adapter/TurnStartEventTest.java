package com.devmanchego.javaguard.adapter;

import com.devmanchego.javaguard.model.AgentFeedback;
import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.HookEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The turn-start event of each agent ({@code UserPromptSubmit} for Claude Code and Codex, {@code BeforeAgent} for Gemini CLI):
 * recognised without a tool or file changes, and answered with nothing the agent would add to the prompt.
 */
class TurnStartEventTest {

    @TempDir
    Path projectDir;

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"CLAUDE,UserPromptSubmit,''", "CODEX,UserPromptSubmit,''", "GEMINI,BeforeAgent,'{}'"})
    void theTurnStartIsRecognisedAndAnsweredSilently(AgentKind agent, String eventName, String passStdout) throws Exception {
        JsonNode payload = new ObjectMapper().readTree("{\"hook_event_name\":\"" + eventName + "\",\"session_id\":\"s1\","
                + "\"cwd\":\"" + projectDir.toString().replace("\\", "/") + "\",\"prompt\":\"fix it\"}");
        ParseContext ctx = new ParseContext(Optional.empty(), projectDir, Instant.EPOCH, PathNormalizer.forCurrentOs());

        HookEvent event = AgentAdapters.forKind(agent).parse(payload, ctx);
        AgentResponse response = AgentAdapters.forKind(agent).render(event.gate(), AgentFeedback.pass());

        assertThat(event.gate()).isEqualTo(GateKind.TURN_START);
        assertThat(event.sessionId()).isEqualTo("s1");
        assertThat(event.toolName()).isEmpty();
        assertThat(event.changes()).isEmpty();
        assertThat(response.exitCode()).isZero();
        assertThat(response.stdout().strip()).isEqualTo(passStdout);
        assertThat(response.stderr()).isEmpty();
    }
}
