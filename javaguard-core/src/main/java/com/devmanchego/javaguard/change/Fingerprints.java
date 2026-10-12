package com.devmanchego.javaguard.change;

import com.devmanchego.javaguard.model.Fingerprint;

/**
 * Computes finding fingerprints (specification &sect;13.1):
 * {@code sha256(ruleId | projectRelativePath | normalizedMessage)}, first 16
 * hex characters. Test failures use
 * {@code sha256(TEST | testFqn#method | exceptionType | normalizedMessage)}.
 *
 * <p>Line numbers never take part, so a finding keeps its identity when an
 * edit above it shifts it down; that is what lets the new-vs-baseline filter
 * (Phase 4) and loop detection (Phase 12) recognize "the same problem".
 */
public final class Fingerprints {

    /**
     * Version of the fingerprint scheme. Stored with persisted fingerprints
     * (baseline cache keys, loop history); a change in normalization or in the
     * hashed fields must bump it so stale state is discarded, not compared.
     */
    public static final String VERSION = "1";

    private static final int HEX_CHARS = 16;

    private Fingerprints() {
    }

    /**
     * @param projectRelativePath project-relative path; {@code '\\'} is
     *                            converted to {@code '/'}; empty when the
     *                            finding has no file
     */
    public static Fingerprint of(String ruleId, String projectRelativePath, String message) {
        String path = projectRelativePath == null ? "" : projectRelativePath.replace('\\', '/');
        return new Fingerprint(Hashing.shortSha256(
                ruleId + "|" + path + "|" + MessageNormalizer.normalize(message), HEX_CHARS));
    }

    /** Fingerprint of a test failure: independent of the file, so a moved test keeps its identity. */
    public static Fingerprint ofTest(String testFqnAndMethod, String exceptionType, String message) {
        return new Fingerprint(Hashing.shortSha256(
                "TEST|" + testFqnAndMethod + "|" + exceptionType + "|" + MessageNormalizer.normalize(message), HEX_CHARS));
    }
}
