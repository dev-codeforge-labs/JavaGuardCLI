package com.devmanchego.javaguard.report;

import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HumanReportWriterTest {

    private static GateResult pass() {
        return new GateResult(GateKind.POST_EDIT, Verdict.PASS, List.of(), List.of(), List.of(), Optional.empty(),
                Optional.empty(), List.of(), Duration.ZERO);
    }

    @Test
    void writesATimestampedFileNamedAfterTheGate(@TempDir Path projectRoot) throws IOException {
        JavaGuardPaths paths = new JavaGuardPaths(projectRoot);
        Instant when = Instant.parse("2026-09-27T10:15:02Z");

        Path written = HumanReportWriter.write(paths, pass(), when);

        assertThat(written.getFileName().toString()).isEqualTo("2026-09-27T10-15-02-post-edit.md");
        assertThat(written).isRegularFile();
        assertThat(Files.readString(written, StandardCharsets.UTF_8)).contains("post-edit");
    }

    @Test
    void alwaysUpdatesLastReportCopy(@TempDir Path projectRoot) throws IOException {
        JavaGuardPaths paths = new JavaGuardPaths(projectRoot);

        HumanReportWriter.write(paths, pass(), Instant.parse("2026-09-27T10:00:00Z"));
        Path second = HumanReportWriter.write(paths,
                new GateResult(GateKind.END_OF_TURN, Verdict.PASS, List.of(), List.of(), List.of(), Optional.empty(),
                        Optional.empty(), List.of(), Duration.ZERO),
                Instant.parse("2026-09-27T11:00:00Z"));

        Path lastReport = paths.reportsDir().resolve("last-report.md");
        assertThat(lastReport).isRegularFile();
        assertThat(Files.readString(lastReport, StandardCharsets.UTF_8))
                .isEqualTo(Files.readString(second, StandardCharsets.UTF_8));
    }

    @Test
    void twoRunsInTheSameSecondDoNotCollide(@TempDir Path projectRoot) {
        // Same instant truncated to seconds would collide; writing must not throw even so
        // (the second write simply overwrites the first, which is acceptable for this timestamp resolution).
        JavaGuardPaths paths = new JavaGuardPaths(projectRoot);
        Instant when = Instant.parse("2026-09-27T10:15:02Z");

        Path first = HumanReportWriter.write(paths, pass(), when);
        Path second = HumanReportWriter.write(paths, pass(), when);

        assertThat(first).isEqualTo(second);
    }
}
