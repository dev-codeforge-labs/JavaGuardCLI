package com.devmanchego.javaguard.adapter;

import java.util.Objects;

/**
 * Exactly what the hook process writes and returns: stdout bytes (UTF-8),
 * stderr bytes (UTF-8) and exit code (contracts &sect;3.3, &sect;4.3, &sect;5.3).
 */
public record AgentResponse(String stdout, String stderr, int exitCode) {

    private static final AgentResponse SILENT = new AgentResponse("", "", 0);

    public AgentResponse {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
    }

    /**
     * Exit 0 with no output. Used when the agent cannot even be identified
     * (fail-open, contracts &sect;1.5) and when JavaGuard is disabled.
     */
    public static AgentResponse silent() {
        return SILENT;
    }
}
