package com.devmanchego.javaguard.build;

import com.devmanchego.javaguard.model.Finding;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Outcome of a build-tool invocation (compile or test run). Findings are already mapped:
 * {@code JAVAC} errors, {@code JG-DEP-003}, {@code TEST} failures, or a single INFRASTRUCTURE
 * finding when the tool failed for a reason JavaGuard could not classify.
 *
 * @param success     the tool exited 0
 * @param executed    false when nothing was run (no tests selected, tool not found)
 * @param testsRun    test cases executed (test runs only)
 * @param findings    what went wrong, empty on success
 * @param notes       degraded modes the user should know about (fallbacks taken, timeouts)
 * @param log         the untruncated tool output, when it was kept
 */
public record BuildResult(boolean success, boolean executed, int testsRun, List<Finding> findings, List<String> notes,
                          Optional<Path> log, Duration elapsed) {

    public BuildResult {
        findings = List.copyOf(findings);
        notes = List.copyOf(notes);
    }

    public static BuildResult notRun(String note) {
        return new BuildResult(true, false, 0, List.of(), List.of(note), Optional.empty(), Duration.ZERO);
    }
}
