package com.devmanchego.javaguard.state;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Configures {@code java.util.logging} to write only to
 * {@code .javaguard/logs/javaguard.log}, rotating at 2&nbsp;MB with 5 files
 * kept.
 *
 * <p><strong>Diagnostics never go to stdout or stderr.</strong> In hook mode
 * those streams are the wire protocol with the AI agent (contracts
 * &sect;1.2-1.3); any console handler attached to the root logger by the JDK
 * defaults is removed here.
 */
public final class LoggingBootstrap {

    private static final int MAX_BYTES_PER_FILE = 2 * 1024 * 1024;
    private static final int FILE_COUNT = 5;

    private LoggingBootstrap() {
    }

    /**
     * Removes the JDK's default console handler so nothing can reach stderr.
     * Hook mode calls this first, before the project root (and therefore the
     * log file) is known: a failure while reading the payload must not leak
     * a log line into the agent's stderr channel.
     */
    public static void silenceConsole() {
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            if (handler instanceof ConsoleHandler) {
                root.removeHandler(handler);
            }
        }
    }

    /**
     * Configures the root logger for this process. Safe to call more than
     * once (later calls are no-ops once a JavaGuard file handler is already
     * installed).
     *
     * @param paths   the project's state layout; {@link JavaGuardPaths#ensureLayout()}
     *                must have been called already (or is called here if the
     *                logs directory is missing).
     * @param verbose when true, the log level is {@code FINE}; otherwise {@code INFO}.
     */
    public static void configure(JavaGuardPaths paths, boolean verbose) {
        Logger root = Logger.getLogger("");

        for (Handler handler : root.getHandlers()) {
            if (handler instanceof ConsoleHandler) {
                root.removeHandler(handler);
            }
            if (handler instanceof JavaGuardFileHandlerMarker) {
                // Already configured for this process.
                root.setLevel(verbose ? Level.FINE : Level.INFO);
                return;
            }
        }

        try {
            java.nio.file.Files.createDirectories(paths.logsDir());
            String pattern = paths.logFile().toAbsolutePath().toString() + ".%g";
            MarkedFileHandler fileHandler = new MarkedFileHandler(
                    pattern, MAX_BYTES_PER_FILE, FILE_COUNT, true);
            fileHandler.setFormatter(new SimpleFormatter());
            fileHandler.setLevel(Level.ALL);
            root.addHandler(fileHandler);
            root.setLevel(verbose ? Level.FINE : Level.INFO);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to configure JavaGuard file logging under " + paths.logsDir(), e);
        }
    }

    /** Marker interface so {@link #configure} can detect it already ran. */
    private interface JavaGuardFileHandlerMarker {
    }

    private static final class MarkedFileHandler extends FileHandler implements JavaGuardFileHandlerMarker {
        MarkedFileHandler(String pattern, int limit, int count, boolean append) throws IOException {
            super(pattern, limit, count, append);
        }
    }
}
