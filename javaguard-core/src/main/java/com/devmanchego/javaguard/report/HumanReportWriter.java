package com.devmanchego.javaguard.report;

import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.state.JavaGuardPaths;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Writes the human report to {@code .javaguard/reports/} (specification
 * &sect;12.3, &sect;19): a timestamped file, kept forever (subject to
 * {@code state.retentionDays} cleanup, Phase 19+), and a {@code last-report.md}
 * copy that always reflects the most recent run.
 */
public final class HumanReportWriter {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")
            .withZone(ZoneOffset.UTC);
    private static final String LAST_REPORT_NAME = "last-report.md";

    private HumanReportWriter() {
    }

    /** Renders and writes the report; returns the path of the timestamped file. */
    public static Path write(JavaGuardPaths paths, GateResult result, Instant generatedAt) {
        String markdown = HumanReportRenderer.render(result, generatedAt);
        try {
            Files.createDirectories(paths.reportsDir());
            Path timestamped = paths.reportsDir().resolve(TIMESTAMP.format(generatedAt) + "-" + result.gate().cliName() + ".md");
            Files.writeString(timestamped, markdown, StandardCharsets.UTF_8);
            Files.writeString(paths.reportsDir().resolve(LAST_REPORT_NAME), markdown, StandardCharsets.UTF_8);
            return timestamped;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write the JavaGuard report under " + paths.reportsDir(), e);
        }
    }
}
