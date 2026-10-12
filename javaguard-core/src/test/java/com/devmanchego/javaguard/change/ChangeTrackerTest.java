package com.devmanchego.javaguard.change;

import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.model.ChangeType;
import com.devmanchego.javaguard.model.EditFragment;
import com.devmanchego.javaguard.model.FileChange;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.HookEvent;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Specification sections 9.1, 9.4 and 19 (locking). */
class ChangeTrackerTest {

    private static final String SESSION = "s-1";
    private static final String ORIGINAL = "class A {\n    int x = 1;\n}\n";

    @TempDir
    Path root;

    private JavaGuardPaths paths;
    private FakeGit git;
    private ChangeTracker tracker;

    /** Records which paths git was asked about; answers from a map. */
    static final class FakeGit implements GitClient {
        final Map<Path, byte[]> heads = new HashMap<>();
        final List<Path> asked = new ArrayList<>();

        @Override
        public synchronized Optional<byte[]> showHead(Path file) {
            asked.add(file.toAbsolutePath().normalize());
            return Optional.ofNullable(heads.get(file.toAbsolutePath().normalize()));
        }
    }

    @BeforeEach
    void setUp() {
        paths = new JavaGuardPaths(root);
        git = new FakeGit();
        tracker = new ChangeTracker(paths, git, Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC));
    }

    private HookEvent event(GateKind gate, FileChange... changes) {
        return new HookEvent(AgentKind.CLAUDE, gate, SESSION, root, Optional.empty(), "Edit", List.of(changes), false,
                Optional.empty(), Instant.EPOCH);
    }

    private Path file(String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    private String baselineText(String rel) throws IOException {
        return tracker.baseline(SESSION, rel).orElseThrow().text();
    }

    @Test
    void preEditSnapshotBeatsEveryOtherSource() throws IOException {
        Path a = file("src/A.java", ORIGINAL);
        EditFragment edit = new EditFragment("int x = 1;", "int x = 2;", false);
        tracker.snapshotBeforeEdit(event(GateKind.PRE_EDIT, FileChange.edit(a, List.of(edit))));
        Files.writeString(a, ORIGINAL.replace("int x = 1;", "int x = 2;"));
        git.heads.put(a, "stale committed content".getBytes(StandardCharsets.UTF_8));

        List<ChangeTracker.TrackedFile> tracked = tracker.recordAfterEdit(event(GateKind.POST_EDIT, FileChange.edit(a, List.of(edit))));

        assertThat(tracked).singleElement().satisfies(t -> {
            assertThat(t.entry().baselineSource()).isEqualTo(BaselineSource.SNAPSHOT);
            assertThat(t.firstTouch()).isFalse();
        });
        assertThat(baselineText("src/A.java")).isEqualTo(ORIGINAL);
        assertThat(git.asked).isEmpty();
    }

    @Test
    void reconstructionIsUsedWithoutAPreEditSnapshot() throws IOException {
        Path a = file("src/A.java", ORIGINAL.replace("int x = 1;", "int x = 2;"));

        List<ChangeTracker.TrackedFile> tracked = tracker.recordAfterEdit(event(GateKind.POST_EDIT,
                FileChange.edit(a, List.of(new EditFragment("int x = 1;", "int x = 2;", false)))));

        assertThat(tracked.get(0).entry().baselineSource()).isEqualTo(BaselineSource.RECONSTRUCTED);
        assertThat(tracked.get(0).entry().approximate()).isFalse();
        assertThat(tracked.get(0).firstTouch()).isTrue();
        assertThat(baselineText("src/A.java")).isEqualTo(ORIGINAL);
        assertThat(git.asked).isEmpty();
    }

    @Test
    void ambiguousReconstructionFallsBackToGitAndIsApproximate() throws IOException {
        Path a = file("src/A.java", "total total\n");
        git.heads.put(a, "legacyTotal legacyTotal\n".getBytes(StandardCharsets.UTF_8));

        List<ChangeTracker.TrackedFile> tracked = tracker.recordAfterEdit(event(GateKind.POST_EDIT,
                FileChange.edit(a, List.of(new EditFragment("legacyTotal", "total", true)))));

        assertThat(tracked.get(0).entry().baselineSource()).isEqualTo(BaselineSource.GIT);
        assertThat(tracked.get(0).entry().approximate()).isTrue();
        assertThat(baselineText("src/A.java")).isEqualTo("legacyTotal legacyTotal\n");
    }

    @Test
    void newFileHasAnEmptyBaselineWithoutAskingGit() throws IOException {
        Path a = file("src/New.java", "class New {}\n");

        tracker.recordAfterEdit(event(GateKind.POST_EDIT, FileChange.rewrite(a, ChangeType.ADD)));

        assertThat(tracker.baseline(SESSION, "src/New.java").orElseThrow().source()).isEqualTo(BaselineSource.EMPTY);
        assertThat(baselineText("src/New.java")).isEmpty();
        assertThat(git.asked).isEmpty();
    }

    @Test
    void wholeFileRewriteWithoutGitHistoryHasAnEmptyBaseline() throws IOException {
        Path a = file("src/A.java", ORIGINAL);

        tracker.recordAfterEdit(event(GateKind.POST_EDIT, FileChange.rewrite(a, ChangeType.UPDATE)));

        assertThat(tracker.baseline(SESSION, "src/A.java").orElseThrow().source()).isEqualTo(BaselineSource.EMPTY);
        assertThat(git.asked).containsExactly(a);
    }

    @Test
    void firstTouchWinsAndLaterRunsOnlyUpdateTheLedger() throws IOException {
        Path a = file("src/A.java", ORIGINAL.replace("int x = 1;", "int x = 2;"));
        tracker.recordAfterEdit(event(GateKind.POST_EDIT,
                FileChange.edit(a, List.of(new EditFragment("int x = 1;", "int x = 2;", false)))));
        Files.writeString(a, ORIGINAL.replace("int x = 1;", "int x = 3;"));

        List<ChangeTracker.TrackedFile> second = tracker.recordAfterEdit(event(GateKind.POST_EDIT,
                FileChange.edit(a, List.of(new EditFragment("int x = 2;", "int x = 3;", false)))));

        assertThat(second.get(0).firstTouch()).isFalse();
        assertThat(second.get(0).entry().gateRuns()).hasSize(2);
        assertThat(second.get(0).entry().lastSeenHash()).isEqualTo(Hashing.sha256(Files.readAllBytes(a)));
        assertThat(baselineText("src/A.java")).isEqualTo(ORIGINAL);
    }

    @Test
    void filesOutsideTheProjectAreIgnored(@TempDir Path elsewhere) throws IOException {
        Path outside = elsewhere.resolve("Outside.java");
        Files.writeString(outside, "class Outside {}");

        assertThat(tracker.recordAfterEdit(event(GateKind.POST_EDIT, FileChange.rewrite(outside, ChangeType.UPDATE))))
                .isEmpty();
        assertThat(tracker.ledger(SESSION).files()).isEmpty();
    }

    @Test
    void deletedFileIsRecordedWithItsGitBaseline() throws IOException {
        Path gone = root.resolve("src/test/java/OldTest.java");
        git.heads.put(gone, "class OldTest {}\n".getBytes(StandardCharsets.UTF_8));

        List<ChangeTracker.TrackedFile> tracked = tracker.recordAfterEdit(event(GateKind.POST_EDIT,
                FileChange.rewrite(gone, ChangeType.DELETE)));

        assertThat(tracked.get(0).entry().deleted()).isTrue();
        assertThat(tracked.get(0).entry().lastSeenHash()).isNull();
        assertThat(baselineText("src/test/java/OldTest.java")).isEqualTo("class OldTest {}\n");
    }

    @Test
    void moveWithoutFragmentsAsksGitForTheOriginalPath() throws IOException {
        Path from = root.resolve("src/old/A.java");
        Path to = file("src/new/A.java", ORIGINAL);
        git.heads.put(from, ORIGINAL.getBytes(StandardCharsets.UTF_8));

        tracker.recordAfterEdit(event(GateKind.POST_EDIT, new FileChange(to, ChangeType.MOVE, Optional.of(from), List.of())));

        assertThat(git.asked).containsExactly(from);
        assertThat(tracker.ledger(SESSION).files()).containsOnlyKeys("src/new/A.java");
        assertThat(baselineText("src/new/A.java")).isEqualTo(ORIGINAL);
    }

    @Test
    void preEditOfAFileThatDoesNotExistYetRecordsAnEmptySnapshot() throws IOException {
        Path a = root.resolve("src/Fresh.java");

        tracker.snapshotBeforeEdit(event(GateKind.PRE_EDIT, FileChange.rewrite(a, ChangeType.UPDATE)));

        assertThat(tracker.baseline(SESSION, "src/Fresh.java").orElseThrow().source()).isEqualTo(BaselineSource.SNAPSHOT);
        assertThat(baselineText("src/Fresh.java")).isEmpty();
    }

    @Test
    void resetBaselinesForgetsTheSession() throws IOException {
        Path a = file("src/New.java", "class New {}\n");
        tracker.recordAfterEdit(event(GateKind.POST_EDIT, FileChange.rewrite(a, ChangeType.ADD)));

        tracker.resetBaselines(SESSION);

        assertThat(tracker.ledger(SESSION).files()).isEmpty();
        assertThat(tracker.baseline(SESSION, "src/New.java")).isEmpty();
    }

    @Test
    void corruptLedgerIsReplacedNotFatal() throws IOException {
        Files.createDirectories(paths.sessionDir(SESSION));
        Files.writeString(paths.sessionDir(SESSION).resolve("ledger.json"), "{ not json");
        Path a = file("src/New.java", "class New {}\n");

        tracker.recordAfterEdit(event(GateKind.POST_EDIT, FileChange.rewrite(a, ChangeType.ADD)));

        assertThat(tracker.ledger(SESSION).files()).containsOnlyKeys("src/New.java");
    }

    @Test
    void concurrentHooksNeverLoseLedgerUpdates() throws Exception {
        int threads = 8;
        int filesPerThread = 25;
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < filesPerThread; i++) {
                file("src/t" + t + "/F" + i + ".java", "class F" + i + " {}\n");
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int thread = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < filesPerThread; i++) {
                    tracker.recordAfterEdit(event(GateKind.POST_EDIT,
                            FileChange.rewrite(root.resolve("src/t" + thread + "/F" + i + ".java"), ChangeType.ADD)));
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(tracker.ledger(SESSION).files()).hasSize(threads * filesPerThread);
    }

    @Test
    void realGitProvidesTheCommittedContent() throws Exception {
        assumeTrue(gitAvailable(), "git is not on PATH");
        ProcessRunner runner = new ProcessRunner();
        Path a = file("src/A.java", ORIGINAL);
        git(runner, "init", "-q");
        git(runner, "-c", "user.email=t@example.org", "-c", "user.name=t", "add", ".");
        git(runner, "-c", "user.email=t@example.org", "-c", "user.name=t", "commit", "-q", "-m", "init");
        Files.writeString(a, "class A { /* rewritten */ }\n");
        ChangeTracker real = new ChangeTracker(paths, new ProcessGitClient(runner, Duration.ofSeconds(10)),
                Clock.systemUTC());

        real.recordAfterEdit(event(GateKind.POST_EDIT, FileChange.rewrite(a, ChangeType.UPDATE)));

        ChangeTracker.Baseline baseline = real.baseline(SESSION, "src/A.java").orElseThrow();
        assertThat(baseline.source()).isEqualTo(BaselineSource.GIT);
        assertThat(baseline.text().replace("\r\n", "\n")).isEqualTo(ORIGINAL);
    }

    @Test
    void realGitOutsideARepositoryYieldsNothing(@TempDir Path notARepo) throws IOException {
        assumeTrue(gitAvailable(), "git is not on PATH");
        Path f = notARepo.resolve("X.java");
        Files.writeString(f, "class X {}");

        assertThat(new ProcessGitClient(new ProcessRunner(), Duration.ofSeconds(10)).showHead(f)).isEmpty();
    }

    private void git(ProcessRunner runner, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        assertThat(runner.run(command, root, Map.of(), Duration.ofSeconds(30)).succeeded())
                .as("git %s", String.join(" ", args)).isTrue();
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessRunner().run(List.of("git", "--version"), Path.of("."), Map.of(), Duration.ofSeconds(10))
                    .succeeded();
        } catch (IOException e) {
            return false;
        }
    }
}
