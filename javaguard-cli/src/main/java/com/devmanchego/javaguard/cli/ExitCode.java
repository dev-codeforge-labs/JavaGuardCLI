package com.devmanchego.javaguard.cli;

/**
 * Exit codes for manual commands ({@code check}, {@code doctor}, ...),
 * specification &sect;17.
 *
 * <p>Hook mode ({@code javaguard hook ...}) does <strong>not</strong> use
 * these: its exit code and output are dictated entirely by the target
 * agent's contract (.private/spec/03-HOOK-CONTRACTS.md) and are produced by the
 * matching {@code AgentAdapter} instead.
 */
public enum ExitCode {

    /** Success, or verdict PASS / ADVISE. */
    SUCCESS(0),
    /** Verdict BLOCK: blocking findings were reported. */
    BLOCK(1),
    /** Command-line usage error. */
    USAGE_ERROR(2),
    /** Configuration file is missing, malformed, or fails validation. */
    CONFIG_ERROR(3),
    /** Environment error: no build tool detected, no JDK compiler module, not a Java project. */
    ENVIRONMENT_ERROR(4),
    /** Internal error. */
    INTERNAL_ERROR(5);

    private final int code;

    ExitCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
