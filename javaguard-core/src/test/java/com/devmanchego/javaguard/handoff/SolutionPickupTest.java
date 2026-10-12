package com.devmanchego.javaguard.handoff;

import com.devmanchego.javaguard.state.JavaGuardPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 13: a solution is picked up when newer than the latest handoff and not consumed (specification section 14.4). */
class SolutionPickupTest {

    @TempDir
    Path root;

    private JavaGuardPaths paths;

    @BeforeEach
    void setUp() throws Exception {
        paths = new JavaGuardPaths(root);
        paths.ensureLayout();
    }

    private Path put(Path file, String content, String time) throws Exception {
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse(time)));
        return file;
    }

    @Test
    void sessionSolutionIsFoundBeforeTheRootOne() throws Exception {
        put(paths.handoffDir().resolve("SOLUTION-sess-1.md"), "from session", "2026-10-05T10:00:00Z");
        put(root.resolve("AI_SOLUTION.md"), "from root", "2026-10-05T10:00:00Z");

        assertThat(SolutionPickup.find(paths, "sess-1").orElseThrow().text()).isEqualTo("from session");
    }

    @Test
    void rootSolutionIsTheFallback() throws Exception {
        put(root.resolve("AI_SOLUTION.md"), "from root", "2026-10-05T10:00:00Z");

        assertThat(SolutionPickup.find(paths, "s1").orElseThrow().text()).isEqualTo("from root");
    }

    @Test
    void aSolutionOlderThanTheLatestHandoffIsStale() throws Exception {
        put(root.resolve("AI_SOLUTION.md"), "old", "2026-10-05T09:00:00Z");
        put(paths.handoffDir().resolve("HANDOFF-s1-20261005-100000.md"), "handoff", "2026-10-05T10:00:00Z");

        assertThat(SolutionPickup.find(paths, "s1")).isEmpty();

        put(root.resolve("AI_SOLUTION.md"), "new", "2026-10-05T11:00:00Z");
        assertThat(SolutionPickup.find(paths, "s1").orElseThrow().text()).isEqualTo("new");
    }

    @Test
    void aConsumedSolutionIsNotDeliveredAgainUntilItChanges() throws Exception {
        Path file = put(root.resolve("AI_SOLUTION.md"), "first", "2026-10-05T10:00:00Z");
        SolutionPickup.markConsumed(SolutionPickup.find(paths, "s1").orElseThrow());

        assertThat(SolutionPickup.find(paths, "s1")).isEmpty();

        Files.writeString(file, "second");
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        assertThat(SolutionPickup.find(paths, "s1").orElseThrow().text()).isEqualTo("second");
    }

    @Test
    void blankFilesAndNoFilesGiveNothing() throws Exception {
        assertThat(SolutionPickup.find(paths, "s1")).isEmpty();
        put(root.resolve("AI_SOLUTION.md"), "  \n", "2026-10-05T10:00:00Z");
        assertThat(SolutionPickup.find(paths, "s1")).isEmpty();
    }
}
