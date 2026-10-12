package com.devmanchego.javaguard.process;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Outcome of one {@link ProcessRunner} invocation (specification &sect;8.1).
 *
 * @param exitCode        present only if the process exited within its timeout
 * @param output          merged stdout+stderr, decoded as UTF-8, in emission
 *                        order; bounded, see {@code outputTruncated}
 * @param timedOut        true when the timeout fired and the process (and its
 *                        descendants) were killed
 * @param outputTruncated true when {@code output} had to drop its earliest
 *                        content to stay within the buffer bound (the tail is
 *                        kept, and a marker is prepended)
 * @param elapsed         wall-clock time from process start to this result
 */
public record ProcessResult(OptionalInt exitCode, String output, boolean timedOut, boolean outputTruncated,
                            Duration elapsed) {

    public ProcessResult {
        Objects.requireNonNull(exitCode, "exitCode");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(elapsed, "elapsed");
        if (timedOut && exitCode.isPresent()) {
            throw new IllegalArgumentException("A timed-out process cannot have an exit code");
        }
    }

    /** True when the process exited normally with code 0. */
    public boolean succeeded() {
        return exitCode.isPresent() && exitCode.getAsInt() == 0;
    }
}
