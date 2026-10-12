package com.devmanchego.javaguard.change;

import com.devmanchego.javaguard.model.Fingerprint;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fingerprints of the findings in a baseline, cached on disk (specification
 * &sect;9.3 step 1, {@code cache/baseline-findings/}). A baseline never
 * changes during a session, but it is re-analyzed on every gate run of every
 * edit to the same file; caching makes that a file read.
 *
 * <p>The key covers everything that could change the answer: fingerprint
 * scheme version, analyzer identity and configuration, the file's path (it
 * is part of every fingerprint) and the baseline's content hash. A cache is
 * never authoritative: any read or write failure is a miss.
 */
public final class BaselineFindingsCache {

    private static final Logger LOG = Logger.getLogger(BaselineFindingsCache.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final Path dir;

    public BaselineFindingsCache(Path dir) {
        this.dir = dir;
    }

    public static String key(String analyzerKey, String relativePath, String contentHash) {
        return Hashing.shortSha256(Fingerprints.VERSION + "|" + analyzerKey + "|" + relativePath + "|" + contentHash, 32);
    }

    public Optional<List<Fingerprint>> get(String key) {
        Path file = dir.resolve(key + ".json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(file.toFile(), STRINGS).stream().map(Fingerprint::new).toList());
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Ignoring unreadable baseline-findings cache entry " + file, e);
            return Optional.empty();
        }
    }

    public void put(String key, List<Fingerprint> fingerprints) {
        try {
            AtomicFiles.write(dir.resolve(key + ".json"),
                    JSON.writeValueAsBytes(fingerprints.stream().map(Fingerprint::value).toList()));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not write baseline-findings cache entry " + key, e);
        }
    }
}
