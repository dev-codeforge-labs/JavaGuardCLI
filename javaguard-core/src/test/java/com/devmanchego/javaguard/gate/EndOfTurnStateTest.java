package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.state.JavaGuardPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 9: the per-session end-of-turn state survives process restarts (each gate run is a new JVM). */
class EndOfTurnStateTest {

    @TempDir
    Path root;

    @Test
    void aSavedStateIsReadBackIntact() {
        EndOfTurnState.Store store = new EndOfTurnState.Store(new JavaGuardPaths(root));
        EndOfTurnState state = new EndOfTurnState(1, "2026-10-05T10:00:00Z", Map.of("a/B.java", "abc", "gone.java", "-"),
                List.of("a/B.java"), 2, 3);

        store.save("s1", state);

        assertThat(new EndOfTurnState.Store(new JavaGuardPaths(root)).load("s1")).contains(state);
    }

    @Test
    void anUnknownSessionHasNoState() {
        assertThat(new EndOfTurnState.Store(new JavaGuardPaths(root)).load("nobody")).isEmpty();
    }

    @Test
    void seedingNeverOverwritesAnExistingState() {
        EndOfTurnState.Store store = new EndOfTurnState.Store(new JavaGuardPaths(root));

        assertThat(store.saveIfAbsent("s1", EndOfTurnState.seeded(Map.of("a", "1")))).isTrue();
        assertThat(store.saveIfAbsent("s1", EndOfTurnState.seeded(Map.of("a", "2")))).isFalse();

        assertThat(store.load("s1").orElseThrow().knownHashes()).containsEntry("a", "1");
    }

    @Test
    void aCorruptStateFileIsTreatedAsNoState() throws Exception {
        JavaGuardPaths paths = new JavaGuardPaths(root);
        Files.createDirectories(paths.sessionDir("s1"));
        Files.writeString(paths.sessionDir("s1").resolve("endofturn.json"), "{ not json");

        assertThat(new EndOfTurnState.Store(paths).load("s1")).isEmpty();
    }

    @Test
    void unknownFieldsFromANewerVersionAreIgnored() throws Exception {
        JavaGuardPaths paths = new JavaGuardPaths(root);
        Files.createDirectories(paths.sessionDir("s1"));
        Files.writeString(paths.sessionDir("s1").resolve("endofturn.json"),
                "{\"version\":2,\"knownHashes\":{\"a\":\"1\"},\"somethingNew\":true}");

        assertThat(new EndOfTurnState.Store(paths).load("s1").orElseThrow().knownHashes()).containsEntry("a", "1");
    }

    @Test
    void hashOfAMissingFileIsTheAbsentSentinel() {
        assertThat(EndOfTurnState.hashOf(root.resolve("nope"))).isEqualTo(EndOfTurnState.ABSENT);
    }
}
