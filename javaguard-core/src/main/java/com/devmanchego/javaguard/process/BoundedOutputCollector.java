package com.devmanchego.javaguard.process;

/**
 * A growing text buffer capped at {@code maxChars}, keeping the tail
 * (specification &sect;8.1: "captured to a bounded buffer ... tail kept").
 *
 * <p>The bound is on UTF-16 characters rather than the exact UTF-8 byte count
 * the specification's "4 MB" refers to; for the ASCII-heavy output real build
 * tools produce this is effectively the same limit, and exactness here would
 * cost avoiding mid-character truncation for negligible benefit.
 *
 * <p>Thread-safe: the output pump thread appends while the caller may not yet
 * have read the result.
 */
final class BoundedOutputCollector {

    private static final String TRUNCATION_MARKER = "... output truncated, showing tail ...\n";

    private final int maxChars;
    private final StringBuilder buffer = new StringBuilder();
    private boolean truncated = false;

    BoundedOutputCollector(int maxChars) {
        if (maxChars < 1) {
            throw new IllegalArgumentException("maxChars must be positive: " + maxChars);
        }
        this.maxChars = maxChars;
    }

    synchronized void append(char[] chars, int length) {
        buffer.append(chars, 0, length);
        if (buffer.length() > maxChars) {
            buffer.delete(0, buffer.length() - maxChars);
            truncated = true;
        }
    }

    synchronized boolean wasTruncated() {
        return truncated;
    }

    @Override
    public synchronized String toString() {
        return truncated ? TRUNCATION_MARKER + buffer : buffer.toString();
    }
}
