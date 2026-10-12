package com.devmanchego.javaguard.change;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Exclusive lock on a session's state (specification &sect;19): agents may
 * run several hooks at once, each in its own JavaGuard process, and every
 * read-modify-write of session state must be serialized.
 *
 * <p>Two layers are needed. A {@link FileLock} serializes processes, but the
 * JVM refuses a second {@code FileLock} on the same file from another thread
 * of the same process ({@link OverlappingFileLockException}) instead of
 * waiting; an in-process {@link ReentrantLock} per lock file serializes
 * threads first. Not reentrant across nested acquisitions of the same file:
 * callers acquire once per operation.
 */
public final class SessionLock implements AutoCloseable {

    /** Lock could not be acquired in time; the caller proceeds without updating state. */
    public static final class TimeoutException extends IOException {
        public TimeoutException(String message) {
            super(message);
        }
    }

    private static final Map<Path, ReentrantLock> IN_PROCESS = new ConcurrentHashMap<>();
    private static final long POLL_MILLIS = 20;

    private final ReentrantLock threadLock;
    private final FileChannel channel;
    private final FileLock fileLock;

    private SessionLock(ReentrantLock threadLock, FileChannel channel, FileLock fileLock) {
        this.threadLock = threadLock;
        this.channel = channel;
        this.fileLock = fileLock;
    }

    public static SessionLock acquire(Path lockFile, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Path key = lockFile.toAbsolutePath().normalize();
        ReentrantLock threadLock = IN_PROCESS.computeIfAbsent(key, k -> new ReentrantLock());
        try {
            if (!threadLock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("Timed out waiting for " + key + " (another thread)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TimeoutException("Interrupted waiting for " + key);
        }
        FileChannel channel = null;
        try {
            Files.createDirectories(key.getParent());
            channel = FileChannel.open(key, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            while (true) {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return new SessionLock(threadLock, channel, lock);
                }
                if (System.nanoTime() > deadline) {
                    throw new TimeoutException("Timed out waiting for " + key + " (another process)");
                }
                Thread.sleep(POLL_MILLIS);
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(channel);
            threadLock.unlock();
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly(channel);
            threadLock.unlock();
            throw new TimeoutException("Interrupted waiting for " + key);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            fileLock.release();
        } finally {
            try {
                channel.close();
            } finally {
                threadLock.unlock();
            }
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // best effort on the failure path
            }
        }
    }
}
