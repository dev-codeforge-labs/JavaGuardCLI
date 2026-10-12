package com.devmanchego.javaguard.consult;

import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 14: a fake consult CLI answers (or fails, or hangs) and the worker records it faithfully. */
class ConsultWorkerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC);
    private static final String SESSION = "sess-w-1";

    @TempDir
    Path root;

    private JavaGuardPaths paths;

    @BeforeEach
    void setUp() throws Exception {
        paths = new JavaGuardPaths(root);
        paths.ensureLayout();
        Files.writeString(paths.handoffDir().resolve("HANDOFF-sess-w-1-20261005-100000.md"), "# JavaGuard handoff - stuck on X\nbody\n",
                StandardCharsets.UTF_8);
    }

    private ConsultStatus consult(String mode, int timeoutSeconds) throws Exception {
        int configured = Math.max(10, timeoutSeconds);
        String yaml = "consult:\n  enabled: true\n  timeoutSeconds: " + configured + "\n  commands:\n    gemini: '"
                + FakeConsult.command(mode) + "'\n";
        Files.writeString(root.resolve("javaguard.yml"), yaml);
        JavaGuardConfig config = ConfigLoader.load(ConfigSources.standard(root, Optional.empty(), root.resolve("home"), Map.of())).config();
        return new ConsultWorker(paths, config, new ProcessRunner(), CLOCK, Map.of(), java.time.Duration.ofSeconds(timeoutSeconds))
                .run(SESSION, AgentKind.GEMINI);
    }

    @Test
    void anAnswerBecomesTheSolutionFileAndTheStatusIsDone() throws Exception {
        ConsultStatus status = consult("ok", 60);

        assertThat(status.state()).as(status.message()).isEqualTo(ConsultStatus.State.DONE);
        String solution = Files.readString(paths.handoffDir().resolve("SOLUTION-sess-w-1.md"), StandardCharsets.UTF_8);
        assertThat(solution).contains("### Diagnosis")
                .contains("handoff-first-line: # JavaGuard handoff - stuck on X") // the handoff arrived on stdin
                .contains("instruction: " + ConsultCommands.INSTRUCTION.split(" ")[0]) // and the instruction as one argument
                .contains("JAVAGUARD_DISABLED=1"); // recursion guard in the child
        assertThat(ConsultStatus.read(paths.sessionDir(SESSION))).contains(status);
    }

    @Test
    void aFailingConsultantIsRecordedAndLeavesNoSolution() throws Exception {
        ConsultStatus status = consult("fail", 60);

        assertThat(status.state()).isEqualTo(ConsultStatus.State.FAILED);
        assertThat(status.exitCode()).isEqualTo(3);
        assertThat(paths.handoffDir().resolve("SOLUTION-sess-w-1.md")).doesNotExist();
    }

    @Test
    void aConsultantThatAnswersNothingFails() throws Exception {
        ConsultStatus status = consult("silent", 60);

        assertThat(status.state()).isEqualTo(ConsultStatus.State.FAILED);
        assertThat(status.message()).contains("nothing");
    }

    @Test
    void aTimeoutKillsTheConsultantAndIsRecordedAsFailure() throws Exception {
        long start = System.nanoTime();
        ConsultStatus status = consult("sleep", 2);

        assertThat(status.state()).isEqualTo(ConsultStatus.State.FAILED);
        assertThat(status.message()).contains("Timed out");
        assertThat((System.nanoTime() - start) / 1_000_000_000L).isLessThan(30);
        assertThat(paths.handoffDir().resolve("SOLUTION-sess-w-1.md")).doesNotExist();
    }

    @Test
    void withoutAHandoffThereIsNothingToSend() throws Exception {
        try (var files = Files.list(paths.handoffDir())) {
            for (Path p : files.toList()) {
                Files.delete(p);
            }
        }

        assertThat(consult("ok", 60).message()).contains("no handoff");
    }

    @Test
    void anUnavailableConsultantIsAFailureNotACrash() throws Exception {
        Files.writeString(root.resolve("javaguard.yml"), "consult:\n  enabled: true\n  commands:\n    gemini: 'no-such-tool-xyz <instruction>'\n");
        JavaGuardConfig config = ConfigLoader.load(ConfigSources.standard(root, Optional.empty(), root.resolve("home"), Map.of())).config();

        ConsultStatus status = new ConsultWorker(paths, config, new ProcessRunner(), CLOCK, Map.of()).run(SESSION, AgentKind.GEMINI);

        assertThat(status.state()).isEqualTo(ConsultStatus.State.FAILED);
        assertThat(status.message()).contains("not found");
    }

    @Test
    void theDetachedRunnerReturnsAtOnceAndTheWorkerOutlivesIt() throws Exception {
        String javaExe = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(FakeConsult.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        DetachedConsultRunner runner = new DetachedConsultRunner(() -> java.util.List.of(javaExe, "-cp", classes, FakeConsult.class.getName(), "marker"));

        runner.start(new ConsultRunner.Launch(root, SESSION, AgentKind.GEMINI));

        Path marker = root.resolve("marker.txt");
        assertThat(marker).doesNotExist(); // the worker sleeps 500 ms: start() did not wait for it
        long deadline = System.currentTimeMillis() + 20_000;
        while (!Files.exists(marker) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(Files.readString(marker)).contains("consult-run --session sess-w-1 --agent gemini");
        assertThat(paths.logsDir().resolve("consult-sess-w-1.log")).exists();
    }
}
