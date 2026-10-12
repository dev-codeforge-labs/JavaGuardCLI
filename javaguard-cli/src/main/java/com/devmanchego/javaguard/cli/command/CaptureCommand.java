package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.adapter.AgentDetector;
import com.devmanchego.javaguard.adapter.PayloadException;
import com.devmanchego.javaguard.adapter.PayloadFields;
import com.devmanchego.javaguard.adapter.PayloadReader;
import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.state.LoggingBootstrap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * Hook-compatible recorder (specification &sect;4): stores the raw stdin
 * payload byte-for-byte plus redacted metadata as a fixture, then exits 0.
 * Install it temporarily as a hook in each agent to verify
 * .private/spec/03-HOOK-CONTRACTS.md against reality (plan Phase 1).
 *
 * <p>Output per invocation, in {@code --out}:
 * <ul>
 *   <li>{@code <stem>.payload.json} — stdin exactly as received;</li>
 *   <li>{@code <stem>.meta.json} — capture time, detected agent, event, tool,
 *       and the agent-related environment variables (redacted).</li>
 * </ul>
 *
 * <p>Like {@code hook}, it never fails the agent: any error is appended to
 * {@code capture-errors.log} in {@code --out} and the command still exits 0.
 * For Gemini it prints {@code {}}, since Gemini expects JSON on stdout.
 */
@Command(mixinStandardHelpOptions = true, name = "capture",
        description = "Record a hook payload as a test fixture (install as a hook temporarily).")
public final class CaptureCommand implements Callable<Integer> {

    /** Only variables that describe the agent session are recorded. */
    private static final Pattern RECORDED_ENV = Pattern.compile("^(CLAUDE_|GEMINI_|CODEX_|JAVAGUARD_).*");
    /**
     * Recorded variables whose names suggest a secret or a personal identifier are redacted (specification section 22).
     * Account and user identifiers such as {@code CLAUDE_CODE_ACCOUNT_UUID} are not secrets, but they must not be
     * copied into fixtures that are shared.
     */
    private static final Pattern SECRET_ENV = Pattern.compile(
            "(?i).*(token|secret|key|password|auth|credential|account|uuid|email|user).*");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Option(names = "--out", required = true, description = "Directory to write the captured fixture into.")
    private Path out;

    @Override
    public Integer call() {
        LoggingBootstrap.silenceConsole();
        Path dir = out.toAbsolutePath().normalize();
        Optional<AgentKind> agent = Optional.empty();
        try {
            byte[] raw = PayloadReader.read(System.in, PayloadReader.DEFAULT_TIMEOUT, PayloadReader.DEFAULT_MAX_BYTES);
            Optional<JsonNode> payload = tryParse(raw);
            agent = payload.flatMap(p -> AgentDetector.detect(p, ProcessEnvironment.get()));
            String event = payload.flatMap(p -> PayloadFields.text(p, "hook_event_name")).orElse("unparsed");
            String tool = payload.flatMap(p -> PayloadFields.text(p, "tool_name")).orElse("");

            Files.createDirectories(dir);
            String stem = uniqueStem(dir, STAMP.format(ZonedDateTime.now(ZoneOffset.UTC)) + "-" + safe(event)
                    + (tool.isEmpty() ? "" : "-" + safe(tool)));
            Files.write(dir.resolve(stem + ".payload.json"), raw);
            Files.writeString(dir.resolve(stem + ".meta.json"),
                    JSON.writeValueAsString(meta(raw, payload.isPresent(), agent, event, tool)), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            recordError(dir, t);
        }
        if (agent.filter(a -> a == AgentKind.GEMINI).isPresent()) {
            byte[] json = "{}\n".getBytes(StandardCharsets.UTF_8);
            System.out.write(json, 0, json.length);
            System.out.flush();
        }
        return 0;
    }

    private static Optional<JsonNode> tryParse(byte[] raw) {
        try {
            return Optional.of(PayloadReader.parseJson(raw));
        } catch (PayloadException e) {
            return Optional.empty();
        }
    }

    private static ObjectNode meta(byte[] raw, boolean parseable, Optional<AgentKind> agent, String event, String tool) {
        ObjectNode meta = JSON.createObjectNode();
        meta.put("capturedAt", ZonedDateTime.now(ZoneOffset.UTC).toString());
        meta.put("detectedAgent", agent.map(AgentKind::cliName).orElse(null));
        meta.put("hookEventName", event);
        meta.put("toolName", tool);
        meta.put("payloadBytes", raw.length);
        meta.put("parseable", parseable);
        meta.put("os", System.getProperty("os.name"));
        ObjectNode env = meta.putObject("environment");
        Map<String, String> sorted = new TreeMap<>(ProcessEnvironment.get());
        sorted.forEach((name, value) -> {
            if (RECORDED_ENV.matcher(name).matches()) {
                env.put(name, SECRET_ENV.matcher(name).matches() ? "<redacted>" : value);
            }
        });
        return meta;
    }

    private static String uniqueStem(Path dir, String base) {
        String stem = base;
        for (int i = 2; Files.exists(dir.resolve(stem + ".payload.json")); i++) {
            stem = base + "-" + i;
        }
        return stem;
    }

    private static String safe(String value) {
        String s = value.replaceAll("[^A-Za-z0-9_-]", "_");
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private static void recordError(Path dir, Throwable t) {
        try {
            Files.createDirectories(dir);
            StringWriter trace = new StringWriter();
            t.printStackTrace(new PrintWriter(trace));
            Files.writeString(dir.resolve("capture-errors.log"),
                    ZonedDateTime.now(ZoneOffset.UTC) + " " + trace + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Nowhere left to report; capture must still exit 0.
        }
    }
}
