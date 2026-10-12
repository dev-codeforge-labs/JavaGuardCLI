package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.cli.Main;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureCommandTest {

    @TempDir
    Path out;

    private InputStream originalIn;
    private PrintStream originalOut;
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        originalIn = System.in;
        originalOut = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        ProcessEnvironment.overrideForTests(Map.of(
                "CLAUDE_PROJECT_DIR", "/work/demo",
                "CLAUDE_API_KEY", "sk-live-secret",
                "GEMINI_AUTH_TOKEN", "abc",
                "CLAUDE_CODE_ACCOUNT_UUID", "2f6c0a1e-0000-4000-8000-000000000000",
                "CLAUDE_CODE_ENTRYPOINT", "cli",
                "HOME", "/home/someone",
                "PATH", "/usr/bin"));
    }

    @AfterEach
    void tearDown() {
        System.setIn(originalIn);
        System.setOut(originalOut);
        ProcessEnvironment.overrideForTests(null);
    }

    private int capture(byte[] payload) {
        System.setIn(new ByteArrayInputStream(payload));
        return new CommandLine(new Main()).execute("capture", "--out", out.toString());
    }

    private List<Path> files(String suffix) throws IOException {
        try (Stream<Path> s = Files.list(out)) {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
        }
    }

    @Test
    void storesThePayloadByteForByte() throws IOException {
        byte[] payload = "{\"hook_event_name\":\"PostToolUse\",\"tool_name\":\"Edit\",\"x\":\"ñ\"}\r\n"
                .getBytes(StandardCharsets.UTF_8);

        int exit = capture(payload);

        assertThat(exit).isZero();
        assertThat(stdout.toByteArray()).isEmpty();
        List<Path> payloads = files(".payload.json");
        assertThat(payloads).singleElement()
                .satisfies(p -> assertThat(p.getFileName().toString()).contains("-PostToolUse-Edit"));
        assertThat(Files.readAllBytes(payloads.get(0))).isEqualTo(payload);
    }

    @Test
    void metadataRecordsOnlyAgentVariablesAndRedactsSecrets() throws IOException {
        capture("{\"hook_event_name\":\"Stop\",\"tool_use_id\":\"t\"}".getBytes(StandardCharsets.UTF_8));

        JsonNode meta = new ObjectMapper().readTree(files(".meta.json").get(0).toFile());
        JsonNode env = meta.path("environment");
        assertThat(meta.path("detectedAgent").asText()).isEqualTo("claude");
        assertThat(meta.path("hookEventName").asText()).isEqualTo("Stop");
        assertThat(meta.path("parseable").asBoolean()).isTrue();
        assertThat(env.path("CLAUDE_PROJECT_DIR").asText()).isEqualTo("/work/demo");
        assertThat(env.path("CLAUDE_API_KEY").asText()).isEqualTo("<redacted>");
        assertThat(env.path("GEMINI_AUTH_TOKEN").asText()).isEqualTo("<redacted>");
        assertThat(env.path("CLAUDE_CODE_ACCOUNT_UUID").asText()).isEqualTo("<redacted>");
        assertThat(env.path("CLAUDE_CODE_ENTRYPOINT").asText()).isEqualTo("cli");
        assertThat(env.has("HOME")).isFalse();
        assertThat(env.has("PATH")).isFalse();
    }

    @Test
    void unparseablePayloadIsStillCaptured() throws IOException {
        capture("definitely not json".getBytes(StandardCharsets.UTF_8));

        assertThat(files(".payload.json")).singleElement()
                .satisfies(p -> assertThat(p.getFileName().toString()).contains("-unparsed"));
        JsonNode meta = new ObjectMapper().readTree(files(".meta.json").get(0).toFile());
        assertThat(meta.path("parseable").asBoolean()).isFalse();
    }

    @Test
    void geminiGetsAJsonObjectOnStdout() {
        capture("{\"hook_event_name\":\"AfterTool\",\"tool_name\":\"write_file\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(stdout.toString(StandardCharsets.UTF_8)).isEqualTo("{}\n");
    }

    @Test
    void repeatedCapturesNeverOverwrite() throws IOException {
        byte[] payload = "{\"hook_event_name\":\"Stop\"}".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 3; i++) {
            capture(payload);
        }

        assertThat(files(".payload.json")).hasSize(3);
    }
}
