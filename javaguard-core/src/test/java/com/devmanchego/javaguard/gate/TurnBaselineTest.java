package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.state.JavaGuardPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The record of the working tree at the start of a turn: what it keeps, what it leaves out, what it does not disturb. */
class TurnBaselineTest {

    @TempDir
    Path project;

    private JavaGuardPaths paths;
    private EndOfTurnState.Store store;

    @BeforeEach
    void setUp() {
        paths = new JavaGuardPaths(project);
        paths.ensureLayout();
        store = new EndOfTurnState.Store(paths);
    }

    private Path write(String relative, String content) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private TurnBaseline baseline(Path... dirty) {
        return new TurnBaseline(paths, () -> Optional.of(Set.of(dirty)), store);
    }

    @Test
    void theFirstTurnRecordsTheRelevantUncommittedFilesAndNothingElse() throws IOException {
        Path java = write("src/main/java/com/acme/A.java", "class A {}");
        Path pom = write("pom.xml", "<project/>");
        Path notes = write("docs/notes.md", "x");
        Path output = write("target/classes/B.java", "class B {}");

        baseline(java, pom, notes, output).record("s1");

        EndOfTurnState state = store.load("s1").orElseThrow();
        assertThat(state.knownHashes()).containsOnlyKeys("src/main/java/com/acme/A.java", "pom.xml");
        assertThat(state.knownHashes().get("pom.xml")).isEqualTo(EndOfTurnState.hashOf(pom));
        assertThat(state.pendingFiles()).isEmpty();
    }

    @Test
    void aProjectWithoutGitRecordsAnEmptyStateAndFailsNothing() {
        new TurnBaseline(paths, Optional::empty, store).record("s1");

        assertThat(store.load("s1")).hasValueSatisfying(state -> assertThat(state.knownHashes()).isEmpty());
    }

    @Test
    void laterTurnsRefreshTheHashesWithoutDisturbingTheRestOfTheState() throws IOException {
        Path java = write("src/main/java/com/acme/A.java", "class A {}");
        store.save("s1", new EndOfTurnState(EndOfTurnState.CURRENT_VERSION, "2026-10-11T10:00:00Z",
                Map.of("src/main/java/com/acme/A.java", "old", "src/main/java/com/acme/Gone.java", "kept"),
                List.of("src/main/java/com/acme/Broken.java"), 2, 3));

        baseline(java).record("s1");

        EndOfTurnState state = store.load("s1").orElseThrow();
        assertThat(state.knownHashes()).containsEntry("src/main/java/com/acme/A.java", EndOfTurnState.hashOf(java))
                .containsEntry("src/main/java/com/acme/Gone.java", "kept");
        assertThat(state.pendingFiles()).containsExactly("src/main/java/com/acme/Broken.java");
        assertThat(state.consecutiveBlocks()).isEqualTo(2);
        assertThat(state.lastBlockingCount()).isEqualTo(3);
        assertThat(state.lastRun()).isEqualTo("2026-10-11T10:00:00Z");
    }

    @Test
    void aFileDeletedBeforeTheTurnIsRecordedAsAbsent() {
        Path gone = project.resolve("src/main/java/com/acme/Gone.java");

        baseline(gone).record("s1");

        assertThat(store.load("s1").orElseThrow().knownHashes())
                .containsEntry("src/main/java/com/acme/Gone.java", EndOfTurnState.ABSENT);
    }
}
