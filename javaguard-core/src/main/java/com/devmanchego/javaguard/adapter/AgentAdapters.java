package com.devmanchego.javaguard.adapter;

import com.devmanchego.javaguard.adapter.claude.ClaudeAdapter;
import com.devmanchego.javaguard.adapter.codex.CodexAdapter;
import com.devmanchego.javaguard.adapter.gemini.GeminiAdapter;
import com.devmanchego.javaguard.model.AgentKind;

/** Adapter lookup by agent kind. */
public final class AgentAdapters {

    private static final AgentAdapter CLAUDE = new ClaudeAdapter();
    private static final AgentAdapter GEMINI = new GeminiAdapter();
    private static final AgentAdapter CODEX = new CodexAdapter();

    private AgentAdapters() {
    }

    /**
     * @throws IllegalArgumentException for {@link AgentKind#MANUAL}, which has
     *                                  no hook protocol
     */
    public static AgentAdapter forKind(AgentKind kind) {
        return switch (kind) {
            case CLAUDE -> CLAUDE;
            case GEMINI -> GEMINI;
            case CODEX -> CODEX;
            case MANUAL -> throw new IllegalArgumentException("MANUAL runs have no hook adapter");
        };
    }
}
