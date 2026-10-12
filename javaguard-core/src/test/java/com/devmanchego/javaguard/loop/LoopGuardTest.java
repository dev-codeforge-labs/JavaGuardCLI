package com.devmanchego.javaguard.loop;

import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.change.SessionLock;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 12: what {@link LoopGuard} persists - the history, the episode, the snapshots - survives process restarts
 * (each gate run is a new JVM) and concurrent runs, and never breaks the gate.
 */
class LoopGuardTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REL = "src/main/java/A.java";

    @TempDir
    Path root;

    private JavaGuardPaths paths;
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        paths = new JavaGuardPaths(root);
        paths.ensureLayout();
        file = root.resolve(REL);
        Files.createDirectories(file.getParent());
    }

    private static Finding finding(String message) {
        return new Finding("JAVAC", "Compilation error", Category.COMPILATION, Severity.CRITICAL, "maven",
                Optional.of(Path.of(REL)), 3, 1, 3, message, "fix", Optional.empty(), Map.of("symbol", "method x()"),
                Fingerprints.of("JAVAC", REL, message));
    }

    private static GateResult blocked(GateKind gate, Finding... findings) {
        return new GateResult(gate, Verdict.BLOCK, List.of(findings), List.of(), List.of(), Optional.empty(), Optional.empty(),
                List.of(), Duration.ZERO);
    }

    private static GateResult passed(GateKind gate) {
        return new GateResult(gate, Verdict.PASS, List.of(), List.of(), List.of(), Optional.empty(), Optional.empty(), List.of(),
                Duration.ZERO);
    }

    /** A fresh guard every time, as every gate run is a fresh JVM. */
    private GateResult run(GateResult result, String content) throws Exception {
        Files.writeString(file, content);
        LoopGuard guard = new LoopGuard(paths, new LoopSettings(3, 6, 5, 12, 3), 3, CLOCK, false);
        return guard.apply(result, new LoopGuard.Input("s1", result.gate(), Map.of(REL, file), false));
    }

    private Path session() {
        return paths.sessionDir("s1");
    }

    @Test
    void theLadderClimbsAcrossFreshGuardInstancesAndTheHistoryRecordsEveryRun() throws Exception {
        GateResult r1 = run(blocked(GateKind.POST_EDIT, finding("cannot find symbol")), "v1");
        GateResult r2 = run(blocked(GateKind.POST_EDIT, finding("cannot find symbol")), "v2");
        GateResult r3 = run(blocked(GateKind.POST_EDIT, finding("cannot find symbol")), "v3");

        assertThat(r1.escalation()).isEmpty();
        assertThat(r2.escalation()).isEmpty();
        assertThat(r3.escalation()).hasValueSatisfying(e -> {
            assertThat(e.level()).isEqualTo(1);
            assertThat(e.detector()).isEqualTo("repeat");
            assertThat(e.guidance()).contains("You have attempted this 3 times with the same result.")
                    .contains("Your previous attempts changed " + REL + " (2 times).")
                    .contains("check 'method x()'");
        });

        List<String> lines = Files.readAllLines(session().resolve("history.jsonl"));
        assertThat(lines).hasSize(3);
        JsonNode third = JSON.readTree(lines.get(2));
        assertThat(third.get("run").asLong()).isEqualTo(3);
        assertThat(third.get("gate").asText()).isEqualTo("post-edit");
        assertThat(third.get("verdict").asText()).isEqualTo("BLOCK");
        assertThat(third.get("blockingCount").asInt()).isEqualTo(1);
        assertThat(third.get("fingerprints")).hasSize(1);
        assertThat(third.get("fileHashes").get(REL).asText()).hasSize(64);
        assertThat(third.get("level").asInt()).isEqualTo(1);
        assertThat(third.get("detector").asText()).isEqualTo("repeat");
        assertThat(third.get("blockingSignature").asText()).isNotBlank();

        JsonNode state = JSON.readTree(session().resolve("escalation.json").toFile());
        assertThat(state.get("nextRun").asLong()).isEqualTo(4);
        assertThat(state.at("/episodes/post-edit/level").asInt()).isEqualTo(1);
    }

    @Test
    void aHaltTurnsTheVerdictIntoHaltWithTheStopInstruction() throws Exception {
        GateResult last = null;
        for (int i = 1; i <= 6; i++) {
            last = run(blocked(GateKind.END_OF_TURN, finding("cannot find symbol")), "v" + i);
        }

        assertThat(last.verdict()).isEqualTo(Verdict.HALT);
        assertThat(last.blocking()).hasSize(1);
        assertThat(last.escalation()).hasValueSatisfying(e -> assertThat(e.guidance()).startsWith("STOP."));
        assertThat(Files.readAllLines(session().resolve("history.jsonl")).get(5)).contains("\"verdict\":\"HALT\"");
    }

    @Test
    void aPassingRunIsRecordedAndEndsTheEpisode() throws Exception {
        run(blocked(GateKind.POST_EDIT, finding("cannot find symbol")), "v1");
        run(blocked(GateKind.POST_EDIT, finding("cannot find symbol")), "v2");
        run(passed(GateKind.POST_EDIT), "v3");

        GateResult after = run(blocked(GateKind.POST_EDIT, finding("cannot find symbol")), "v4");

        assertThat(after.escalation()).isEmpty();
        assertThat(JSON.readTree(session().resolve("escalation.json").toFile()).at("/episodes/post-edit/blockedRuns").asInt())
                .isEqualTo(1);
    }

    @Test
    void blockedRunsAreSnapshottedAndOnlyTheNewestAreKept() throws Exception {
        for (int i = 1; i <= 5; i++) {
            run(blocked(GateKind.POST_EDIT, finding("problem " + i)), "content " + i);
        }
        run(passed(GateKind.POST_EDIT), "content 6");

        Path snapshots = session().resolve("snapshots");
        List<String> kept;
        try (Stream<Path> dirs = Files.list(snapshots)) {
            kept = dirs.map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertThat(kept).containsExactly("3", "4", "5"); // maxSnapshots = 3; passing runs are not snapshotted
        JsonNode index = JSON.readTree(snapshots.resolve("5").resolve("index.json").toFile());
        String stored = index.get(REL).asText();
        assertThat(snapshots.resolve("5").resolve(stored)).hasContent("content 5");
    }

    @Test
    void aCorruptHistoryLineIsSkippedNotFatal() throws Exception {
        run(blocked(GateKind.POST_EDIT, finding("cannot find symbol")), "v1");
        Files.writeString(session().resolve("history.jsonl"), "{ torn line\n", java.nio.file.StandardOpenOption.APPEND);
        run(blocked(GateKind.POST_EDIT, finding("cannot find symbol")), "v2");

        GateResult third = run(blocked(GateKind.POST_EDIT, finding("cannot find symbol")), "v3");

        assertThat(third.escalation()).hasValueSatisfying(e -> assertThat(e.level()).isEqualTo(1));
    }

    @Test
    void aCorruptEscalationFileStartsOverInsteadOfFailingTheGate() throws Exception {
        run(blocked(GateKind.POST_EDIT, finding("cannot find symbol")), "v1");
        Files.writeString(session().resolve("escalation.json"), "not json");

        GateResult result = run(blocked(GateKind.POST_EDIT, finding("cannot find symbol")), "v2");

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
    }

    @Test
    void whenTheSessionLockCannotBeHadTheResultPassesThroughUnchanged() throws Exception {
        Files.createDirectories(session());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (SessionLock ignored = SessionLock.acquire(session().resolve(".lock"), Duration.ofSeconds(5))) {
                locked.countDown();
                release.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        holder.start();
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        GateResult input = blocked(GateKind.POST_EDIT, finding("cannot find symbol"));

        GateResult result = run(input, "v1");

        release.countDown();
        holder.join();
        assertThat(result).isSameAs(input);
        assertThat(session().resolve("history.jsonl")).doesNotExist();
    }

    @Test
    void concurrentRunsNeitherLoseNorCorruptHistoryLines() throws Exception {
        int runs = 12;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<?>> futures = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < runs; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                LoopGuard guard = new LoopGuard(paths, new LoopSettings(3, 6, 5, 100, 3), 3, CLOCK, false);
                Path own = root.resolve("src/main/java/F" + n + ".java");
                Files.createDirectories(own.getParent());
                Files.writeString(own, "class F" + n + " {}");
                return guard.apply(blocked(GateKind.POST_EDIT, finding("problem " + n)),
                        new LoopGuard.Input("s1", GateKind.POST_EDIT, Map.of("src/main/java/F" + n + ".java", own), false));
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        List<String> lines = Files.readAllLines(session().resolve("history.jsonl"));
        assertThat(lines).hasSize(runs);
        Set<Long> numbers = new HashSet<>();
        for (String line : lines) {
            numbers.add(JSON.readTree(line).get("run").asLong());
        }
        assertThat(numbers).hasSize(runs).allMatch(n -> n >= 1 && n <= runs);
        assertThat(JSON.readTree(session().resolve("escalation.json").toFile()).get("nextRun").asLong()).isEqualTo(runs + 1);
    }

    @Test
    void theStateSurvivesRealProcessRestarts() throws Exception {
        List<String> outputs = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            ProcessBuilder builder = new ProcessBuilder(javaExecutable(), "-cp", classpath(), LoopGuardRunHelper.class.getName(),
                    root.toString(), "class A { int v = " + i + "; }").redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertThat(process.waitFor()).as(output).isZero();
            outputs.add(output.lines().reduce((a, b) -> b).orElse(""));
        }

        assertThat(outputs).containsExactly("BLOCK 0", "BLOCK 0", "BLOCK 1");
    }

    private static String javaExecutable() {
        return ProcessHandle.current().info().command().orElseThrow();
    }

    private static String classpath() throws Exception {
        Path testClasses = Path.of(LoopGuardRunHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path mainClasses = Path.of(LoopGuard.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return String.join(File.pathSeparator, testClasses.toString(), mainClasses.toString(),
                System.getProperty("java.class.path"));
    }
}
