package com.devmanchego.javaguard.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Windows-safe process execution (specification &sect;8.1): explicit argument
 * lists (never a shell string), UTF-8 output decoding regardless of the
 * console code page, a bounded in-memory buffer with an optional untruncated
 * tee file, a hard timeout that kills the whole process tree, and the
 * recursion guard set on every child.
 */
public final class ProcessRunner {

    /** Specification &sect;8.1 default. */
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 4 * 1024 * 1024;

    private static final Logger LOG = Logger.getLogger(ProcessRunner.class.getName());
    private static final Duration PUMP_DRAIN_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration KILL_WAIT_TIMEOUT = Duration.ofSeconds(2);

    public ProcessResult run(List<String> command, Path workingDir, Map<String, String> extraEnv, Duration timeout)
            throws IOException {
        return run(command, workingDir, extraEnv, timeout, DEFAULT_MAX_OUTPUT_CHARS, Optional.empty());
    }

    /**
     * @param command      argument list; {@code command.get(0)} is the executable,
     *                     launched directly (never through {@code cmd /c} or a shell string)
     * @param workingDir   must already exist
     * @param extraEnv     added to the inherited environment; {@code JAVAGUARD_DISABLED=1}
     *                     is always added afterward and cannot be overridden by this map
     * @param timeout      hard wall-clock limit; on expiry the process and every
     *                     descendant are forcibly killed
     * @param maxOutputChars bound for the in-memory result (see {@link BoundedOutputCollector})
     * @param teeFile      when present, the complete, untruncated output is also
     *                     written there as it arrives (for human logs)
     */
    public ProcessResult run(List<String> command, Path workingDir, Map<String, String> extraEnv, Duration timeout,
                             int maxOutputChars, Optional<Path> teeFile) throws IOException {
        return run(command, workingDir, extraEnv, timeout, maxOutputChars, teeFile, Optional.empty());
    }

    /** As above, with {@code stdin} written to the child's standard input (then closed) from a separate thread. */
    public ProcessResult run(List<String> command, Path workingDir, Map<String, String> extraEnv, Duration timeout,
                             int maxOutputChars, Optional<Path> teeFile, Optional<byte[]> stdin) throws IOException {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDir.toFile());
        builder.redirectErrorStream(true);
        builder.environment().putAll(extraEnv);
        // Recursion guard (specification section 1.1 rule 6): anything JavaGuard
        // spawns must never itself re-enter a hook.
        builder.environment().put("JAVAGUARD_DISABLED", "1");

        Instant start = Instant.now();
        Process process = builder.start();
        feedStdin(process, stdin);
        BoundedOutputCollector collector = new BoundedOutputCollector(maxOutputChars);
        OutputStream tee = openTee(teeFile);
        Thread pump = startOutputPump(process.getInputStream(), collector, tee);

        boolean finished = waitQuietly(process, timeout);
        if (!finished) {
            killTree(process);
        }
        joinQuietly(pump, PUMP_DRAIN_TIMEOUT);
        closeQuietly(tee);

        Duration elapsed = Duration.between(start, Instant.now());
        OptionalInt exitCode = finished ? OptionalInt.of(process.exitValue()) : OptionalInt.empty();
        return new ProcessResult(exitCode, collector.toString(), !finished, collector.wasTruncated(), elapsed);
    }

    private static void feedStdin(Process process, Optional<byte[]> stdin) {
        Thread t = new Thread(() -> {
            try (OutputStream out = process.getOutputStream()) {
                if (stdin.isPresent()) {
                    out.write(stdin.get());
                }
            } catch (IOException e) {
                // The child may exit or close its input before reading everything; that is its prerogative.
                LOG.log(Level.FINEST, "Process input stream closed.", e);
            }
        }, "javaguard-process-input");
        t.setDaemon(true);
        t.start();
    }

    private static boolean waitQuietly(Process process, Duration timeout) {
        try {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Kills the process tree: descendants are snapshotted and killed before
     * the root, since a dead root can no longer be asked for its children.
     */
    private static void killTree(Process process) {
        List<ProcessHandle> descendants = process.toHandle().descendants().collect(Collectors.toList());
        for (ProcessHandle h : descendants) {
            h.destroyForcibly();
        }
        process.destroyForcibly();
        for (ProcessHandle h : descendants) {
            waitForExit(h);
        }
        waitForExit(process.toHandle());
    }

    private static void waitForExit(ProcessHandle handle) {
        try {
            handle.onExit().get(KILL_WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Process " + handle.pid() + " did not confirm exit after being killed.", e);
        }
    }

    private static Thread startOutputPump(InputStream in, BoundedOutputCollector collector, OutputStream tee) {
        Thread t = new Thread(() -> pump(in, collector, tee), "javaguard-process-output");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void pump(InputStream in, BoundedOutputCollector collector, OutputStream tee) {
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                collector.append(buf, n);
                if (tee != null) {
                    tee.write(new String(buf, 0, n).getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            // The stream closes when a killed process's pipe is torn down; nothing more to read.
            LOG.log(Level.FINEST, "Process output stream closed.", e);
        }
    }

    private static OutputStream openTee(Optional<Path> teeFile) throws IOException {
        if (teeFile.isEmpty()) {
            return null;
        }
        return Files.newOutputStream(teeFile.get(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private static void closeQuietly(OutputStream out) {
        if (out == null) {
            return;
        }
        try {
            out.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Failed to close process tee file.", e);
        }
    }

    private static void joinQuietly(Thread t, Duration timeout) {
        try {
            t.join(timeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
