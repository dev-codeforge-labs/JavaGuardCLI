package com.devmanchego.javaguard.cli;

import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Enforces the fail-open principle for hook mode (specification &sect;1.1
 * rule 3, contracts &sect;1.5): whatever goes wrong inside JavaGuardCLI, the
 * developer's AI agent is never blocked because of it.
 *
 * <p>Any {@link Throwable} escaping the wrapped {@code body} is logged (to
 * the file logger only, never to stdout/stderr, since those streams carry the
 * hook protocol) and swallowed; the {@code fallback} result is returned
 * instead. A broken guard must be invisible to the agent, not a new source of
 * failures.
 */
public final class FailOpenHookExecutor {

    private FailOpenHookExecutor() {
    }

    /**
     * Runs {@code body} and returns its result; on any throwable, or a
     * {@code null} result, returns {@code fallback.get()}.
     */
    public static <T> T execute(Logger logger, Callable<T> body, Supplier<T> fallback) {
        try {
            T result = body.call();
            return result != null ? result : fallback.get();
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "JavaGuard internal error in hook mode; failing open.", t);
            return fallback.get();
        }
    }
}
