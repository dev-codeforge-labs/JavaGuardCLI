package com.devmanchego.javaguard.adapter;

import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.model.HookEvent;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every payload fixture parses to its expected {@link HookEvent}
 * (plan Phase 1: "parse every fixture -> expected HookEvent"), and the agent
 * auto-detection (contracts section 6) identifies the fixture's own agent.
 */
class AdapterParseGoldenTest {

    @TempDir
    static Path projectDir;

    static Stream<Arguments> fixtures() {
        return Stream.of(AgentKind.CLAUDE, AgentKind.GEMINI, AgentKind.CODEX)
                .flatMap(agent -> PayloadFixtures.names(agent.cliName()).stream()
                        .map(name -> Arguments.of(agent, name)));
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("fixtures")
    void parsesToExpectedEvent(AgentKind agent, String name) throws PayloadException {
        JsonNode payload = PayloadFixtures.payload(agent.cliName(), name, projectDir);
        ParseContext ctx = new ParseContext(Optional.empty(), projectDir, Instant.EPOCH, PathNormalizer.forCurrentOs());

        HookEvent event = AgentAdapters.forKind(agent).parse(payload, ctx);

        assertThat(PayloadFixtures.toJson(event, projectDir))
                .isEqualTo(PayloadFixtures.expected(agent.cliName(), name));
        assertThat(event.receivedAt()).isEqualTo(Instant.EPOCH);
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("fixtures")
    void autoDetectionIdentifiesTheFixturesAgent(AgentKind agent, String name) {
        JsonNode payload = PayloadFixtures.payload(agent.cliName(), name, projectDir);

        assertThat(AgentDetector.detect(payload, Map.of())).contains(agent);
    }
}
