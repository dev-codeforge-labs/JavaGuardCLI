package com.devmanchego.javaguard.adapter;

import com.devmanchego.javaguard.model.AgentFeedback;
import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.model.GateKind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exact stdout/stderr/exit code for every gate x verdict case per agent
 * (contracts sections 3.3, 4.3, 5.3). Goldens: src/test/resources/render/.
 * Regenerate deliberately with {@code -Djavaguard.updateGoldens=true}.
 */
class AdapterRenderGoldenTest {

    static final String ADVISE_REPORT = """
            JAVAGUARD ADVISE - 0 blocking, 1 advisory - post-edit - 1 file(s)
            1. [JG-PERF-001 String concatenation in loop] src/main/java/com/acme/ReportBuilder.java:118
               Fix: use a StringBuilder declared before the loop.""";

    static final String BLOCK_REPORT = """
            JAVAGUARD BLOCK - 1 blocking, 0 advisory - post-edit - 1 file(s)
            1. [JG-SEC-001 SQL built by concatenation] src/main/java/com/acme/UserDao.java:42
               > String sql = "SELECT * FROM users WHERE name = '" + name + "'";
               Fix: use a PreparedStatement.""";

    static final String HALT_MESSAGE =
            "JavaGuard halted the agent: the same blocking problem persisted for 5 attempts. Handoff: AI_HANDOFF_CONTEXT.md";

    static final String NOTICE =
            "New suppression added this session: JG-SEC-001 at src/main/java/com/acme/UserDao.java:41";

    private static final String INTERNAL_ERROR = "internal-error";

    /** Case id -> how to render it. Order = golden file order. */
    static Map<String, Function<AgentAdapter, AgentResponse>> cases() {
        Map<String, Function<AgentAdapter, AgentResponse>> cases = new LinkedHashMap<>();
        cases.put("pre-edit PASS", a -> a.render(GateKind.PRE_EDIT, AgentFeedback.pass()));
        cases.put("pre-edit BLOCK", a -> a.render(GateKind.PRE_EDIT, AgentFeedback.block(BLOCK_REPORT)));
        for (GateKind gate : List.of(GateKind.POST_EDIT, GateKind.END_OF_TURN)) {
            String g = gate.cliName();
            cases.put(g + " PASS", a -> a.render(gate, AgentFeedback.pass()));
            cases.put(g + " ADVISE", a -> a.render(gate, AgentFeedback.advise(ADVISE_REPORT, "")));
            cases.put(g + " BLOCK", a -> a.render(gate, AgentFeedback.block(BLOCK_REPORT)));
            cases.put(g + " HALT", a -> a.render(gate, AgentFeedback.halt(BLOCK_REPORT, HALT_MESSAGE)));
        }
        cases.put("post-edit ADVISE notices+bell", a -> a.render(GateKind.POST_EDIT,
                AgentFeedback.advise(ADVISE_REPORT, "").withUserNotices(List.of(NOTICE)).withBell(true)));
        cases.put("post-edit BLOCK notices+bell", a -> a.render(GateKind.POST_EDIT,
                AgentFeedback.block(BLOCK_REPORT).withUserNotices(List.of(NOTICE)).withBell(true)));
        cases.put("end-of-turn ADVISE notices", a -> a.render(GateKind.END_OF_TURN,
                AgentFeedback.advise(ADVISE_REPORT, "").withUserNotices(List.of(NOTICE))));
        cases.put("post-edit PASS notices", a -> a.render(GateKind.POST_EDIT,
                AgentFeedback.pass().withUserNotices(List.of(NOTICE))));
        cases.put("end-of-turn PASS notices", a -> a.render(GateKind.END_OF_TURN,
                AgentFeedback.pass().withUserNotices(List.of(NOTICE))));
        cases.put("pre-edit PASS notices", a -> a.render(GateKind.PRE_EDIT,
                AgentFeedback.pass().withUserNotices(List.of(NOTICE))));
        cases.put(INTERNAL_ERROR, AgentAdapter::renderInternalError);
        return cases;
    }

    @ParameterizedTest
    @EnumSource(value = AgentKind.class, names = {"CLAUDE", "GEMINI", "CODEX"})
    void rendersExactlyTheGoldenOutput(AgentKind agent) throws IOException {
        AgentAdapter adapter = AgentAdapters.forKind(agent);
        Map<String, AgentResponse> actual = new LinkedHashMap<>();
        cases().forEach((id, render) -> actual.put(id, render.apply(adapter)));

        Path golden = PayloadFixtures.resourcePath("render/" + agent.cliName() + ".golden");
        if (Boolean.getBoolean("javaguard.updateGoldens")) {
            Path source = Path.of("src/test/resources/render/" + agent.cliName() + ".golden");
            Files.writeString(source, write(header(golden), actual), StandardCharsets.UTF_8);
            return;
        }

        Map<String, AgentResponse> expected = parse(Files.readString(golden, StandardCharsets.UTF_8));
        assertThat(expected.keySet()).as("golden case ids").containsExactlyElementsOf(actual.keySet());
        actual.forEach((id, response) -> {
            AgentResponse want = expected.get(id);
            assertThat(response.exitCode()).as("%s %s exit", agent, id).isEqualTo(want.exitCode());
            assertThat(response.stdout()).as("%s %s stdout", agent, id).isEqualTo(want.stdout());
            assertThat(response.stderr()).as("%s %s stderr", agent, id).isEqualTo(want.stderr());
        });
    }

    // --- golden file format (see render/README.md) ---

    static Map<String, AgentResponse> parse(String text) {
        Map<String, AgentResponse> result = new LinkedHashMap<>();
        String id = null;
        int exit = 0;
        List<String> out = new ArrayList<>();
        List<String> err = new ArrayList<>();
        List<String> target = null;
        String body = text.replace("\r\n", "\n");
        if (body.endsWith("\n")) {
            body = body.substring(0, body.length() - 1);
        }
        for (String l : body.split("\n", -1)) {
            if (l.startsWith("=== ")) {
                if (id != null) {
                    result.put(id, new AgentResponse(join(out), join(err), exit));
                }
                id = l.substring(4).strip();
                exit = 0;
                out = new ArrayList<>();
                err = new ArrayList<>();
                target = null;
            } else if (id == null) {
                continue; // header comments
            } else if (l.startsWith("exit: ")) {
                exit = Integer.parseInt(l.substring(6).strip());
            } else if (l.equals("--- stdout")) {
                target = out;
            } else if (l.equals("--- stderr")) {
                target = err;
            } else if (target != null) {
                target.add(l);
            }
        }
        if (id != null) {
            result.put(id, new AgentResponse(join(out), join(err), exit));
        }
        return result;
    }

    private static String join(List<String> lines) {
        return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
    }

    private static String header(Path golden) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readAllLines(golden, StandardCharsets.UTF_8)) {
            if (line.startsWith("=== ")) {
                break;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String write(String header, Map<String, AgentResponse> responses) {
        StringBuilder sb = new StringBuilder(header);
        responses.forEach((id, r) -> {
            sb.append("=== ").append(id).append('\n');
            sb.append("exit: ").append(r.exitCode()).append('\n');
            sb.append("--- stdout\n").append(r.stdout());
            sb.append("--- stderr\n").append(r.stderr());
        });
        return sb.toString();
    }
}
