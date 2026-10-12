package com.devmanchego.javaguard.cli.command;

import java.util.Map;

/**
 * The process environment, overridable in tests. JavaGuard's own test JVM
 * usually runs inside an AI agent that exports {@code CLAUDE_PROJECT_DIR} and
 * similar variables; tests must not depend on that.
 */
final class ProcessEnvironment {

    private static volatile Map<String, String> override;

    private ProcessEnvironment() {
    }

    static Map<String, String> get() {
        Map<String, String> o = override;
        return o != null ? o : System.getenv();
    }

    /** Test hook: {@code null} restores the real environment. */
    static void overrideForTests(Map<String, String> environment) {
        override = environment == null ? null : Map.copyOf(environment);
    }
}
