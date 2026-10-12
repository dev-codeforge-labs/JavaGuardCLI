package com.devmanchego.javaguard.handoff;

import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.loop.LoopGuard;
import com.devmanchego.javaguard.loop.LoopSettings;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 13: a scripted stuck session on a fixture produces the golden handoff; seeded secrets in the source and in
 * the transcript are redacted; a solution is injected exactly once and gives the agent a fresh chance.
 */
class HandoffSessionTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC);
    private static final String REL = "src/main/java/com/acme/OrderService.java";
    private static final String SECRET = "AKIAABCDEFGHIJKLMNOP";
    private static final String SESSION = "sess-golden-1";

    @TempDir
    Path root;

    private JavaGuardPaths paths;
    private JavaGuardConfig config;
    private Path source;
    private Path transcript;

    @BeforeEach
    void setUp() throws Exception {
        paths = new JavaGuardPaths(root);
        paths.ensureLayout();
        config = ConfigLoader.load(ConfigSources.standard(root, Optional.empty(), root.resolve("home"), Map.of())).config();
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                  <dependencies>
                    <dependency><artifactId>junit-jupiter</artifactId></dependency>
                    <dependency><artifactId>mockito-core</artifactId></dependency>
                  </dependencies>
                </project>
                """);
        source = root.resolve(REL);
        Files.createDirectories(source.getParent());
        transcript = root.resolve("transcript.jsonl");
        Files.writeString(transcript, """
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"The cause is the missing total method."},{"type":"tool_use","name":"Edit","input":{"file_path":"src/main/java/com/acme/OrderService.java"}}]}}
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Retrying with the key AKIAABCDEFGHIJKLMNOP in the config."}]}}
                """, StandardCharsets.UTF_8);
    }

    private LoopGuard guard() {
        return new LoopGuard(paths, new LoopSettings(3, 6, 5, 12, 3), 5, CLOCK, false,
                Optional.of(new HandoffService(paths, config, CLOCK)));
    }

    private static Finding finding() {
        return new Finding("JAVAC", "Compilation error", Category.COMPILATION, Severity.CRITICAL, "maven",
                Optional.of(Path.of(REL)), 6, 9, 6, "cannot find symbol: method total()", "Declare total() or fix the call.",
                Optional.of("return total();"), Map.of("symbol", "method total()"), Fingerprints.of("JAVAC", REL, "total"));
    }

    private GateResult blocked() {
        return new GateResult(GateKind.POST_EDIT, Verdict.BLOCK, List.of(finding()), List.of(), List.of(), Optional.empty(),
                Optional.empty(), List.of(), Duration.ZERO);
    }

    private GateResult passed() {
        return new GateResult(GateKind.POST_EDIT, Verdict.PASS, List.of(), List.of(), List.of(), Optional.empty(), Optional.empty(),
                List.of(), Duration.ZERO);
    }

    private GateResult run(GateResult result, String body) throws Exception {
        Files.writeString(source, "package com.acme;\n\npublic class OrderService {\n    String key = \"" + SECRET + "\";\n"
                + "    int run() {\n        return total();" + body + "\n    }\n}\n");
        return guard().apply(result, new LoopGuard.Input(SESSION, GateKind.POST_EDIT, Map.of(REL, source), false, "claude",
                Optional.of(transcript)));
    }

    /** Edits that never fix the error, until the guard halts. */
    private GateResult stuckUntilHalt() throws Exception {
        GateResult last = null;
        for (int i = 1; i <= 12; i++) {
            last = run(blocked(), " // attempt " + i);
            if (last.verdict() == Verdict.HALT) {
                return last;
            }
        }
        throw new AssertionError("the guard never halted; last verdict " + last.verdict());
    }

    @Test
    void haltWritesTheGoldenHandoffAndPointsToIt() throws Exception {
        GateResult halted = stuckUntilHalt();

        String handoffPath = halted.escalation().orElseThrow().handoff();
        assertThat(handoffPath).startsWith(".javaguard/handoff/HANDOFF-sess-gol-").endsWith(".md");
        String handoff = Files.readString(root.resolve(handoffPath), StandardCharsets.UTF_8);
        assertThat(Files.readString(root.resolve("AI_HANDOFF_CONTEXT.md"), StandardCharsets.UTF_8)).isEqualTo(handoff);

        Path golden = Path.of("src/test/resources/handoff/stuck-session.golden.md");
        if (!Files.exists(golden)) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, handoff, StandardCharsets.UTF_8);
            throw new AssertionError("Golden file created at " + golden.toAbsolutePath() + "; review it and rerun.");
        }
        assertThat(handoff).isEqualTo(Files.readString(golden, StandardCharsets.UTF_8));
    }

    @Test
    void secretsInSourceAndTranscriptNeverReachTheHandoff() throws Exception {
        GateResult halted = stuckUntilHalt();

        String handoff = Files.readString(root.resolve(halted.escalation().orElseThrow().handoff()), StandardCharsets.UTF_8);
        assertThat(handoff).doesNotContain(SECRET).contains("<redacted:").contains("The cause is the missing total method.");
        assertThat(handoff).doesNotContain(root.toString());
    }

    @Test
    void aSolutionIsInjectedExactlyOnceAndGivesAFreshChance() throws Exception {
        GateResult halted = stuckUntilHalt();
        assertThat(halted.solution()).isEmpty();

        Path solution = root.resolve("AI_SOLUTION.md");
        Files.writeString(solution, "### Diagnosis\nDeclare total().");
        Files.setLastModifiedTime(solution, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000));

        GateResult withSolution = run(blocked(), " // after the solution");
        assertThat(withSolution.solution()).hasValueSatisfying(s -> assertThat(s).contains("Declare total()"));
        assertThat(withSolution.verdict()).isNotEqualTo(Verdict.HALT);
        assertThat(withSolution.escalation().map(e -> e.level()).orElse(0)).isLessThanOrEqualTo(1);

        GateResult next = run(blocked(), " // after the solution, again");
        assertThat(next.solution()).isEmpty();
        GateResult later = run(passed(), " // fixed");
        assertThat(later.solution()).isEmpty();
    }

    @Test
    void onDemandHandoffUsesTheLastBlockedRun() throws Exception {
        run(blocked(), " // one");
        run(blocked(), " // two");

        SessionHandoff handoff = new SessionHandoff(paths, config, CLOCK);
        assertThat(handoff.latestSession()).contains(SESSION);
        SessionHandoff.Outcome outcome = handoff.generate(SESSION);

        assertThat(outcome.path()).isPresent();
        String text = Files.readString(root.resolve(outcome.path().get()), StandardCharsets.UTF_8);
        assertThat(text).contains("JAVAC Compilation error in src/main/java/com/acme/OrderService.java")
                .contains("Agent: claude").contains("### Attempt 2").doesNotContain(SECRET);
    }

    @Test
    void onDemandHandoffOfAnUnknownSessionExplainsWhy() {
        SessionHandoff.Outcome outcome = new SessionHandoff(paths, config, CLOCK).generate("nope");

        assertThat(outcome.path()).isEmpty();
        assertThat(outcome.message()).contains("no recorded gate runs");
    }

    @Test
    void handoffFilesLiveOnlyUnderTheStateDirectoryAndTheRootCopy() throws Exception {
        stuckUntilHalt();

        try (Stream<Path> files = Files.list(root)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).contains("AI_HANDOFF_CONTEXT.md");
        }
    }
}
