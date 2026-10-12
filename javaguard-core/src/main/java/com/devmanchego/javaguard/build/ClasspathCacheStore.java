package com.devmanchego.javaguard.build;

import com.devmanchego.javaguard.change.Hashing;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads and writes one module's {@link ClasspathCache} at
 * {@code .javaguard/cache/classpath/<module-hash>.json} (specification
 * &sect;8.3), and decides whether it is stale: any build file it recorded a
 * hash for has changed, been removed, or a new one now exists on the parent
 * chain up to the reactor root. Like the other caches in this project
 * (compare {@code change.BaselineFindingsCache}), it is never authoritative:
 * any read/write/hash failure is treated as a miss or as staleness, never
 * as an error that stops the gate.
 */
public final class ClasspathCacheStore {

    private static final Logger LOG = Logger.getLogger(ClasspathCacheStore.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path classpathCacheDir;

    public ClasspathCacheStore(Path classpathCacheDir) {
        this.classpathCacheDir = classpathCacheDir;
    }

    /** Stable key for {@code moduleRoot}, also the cache file's base name. */
    public static String keyFor(Path moduleRoot) {
        return Hashing.shortSha256(moduleRoot.toAbsolutePath().normalize().toString().replace('\\', '/'), 16);
    }

    public Optional<ClasspathCache> get(Path moduleRoot) {
        Path file = classpathCacheDir.resolve(keyFor(moduleRoot) + ".json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(file.toFile(), ClasspathCache.class));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Ignoring unreadable classpath cache entry " + file, e);
            return Optional.empty();
        }
    }

    public void put(Path moduleRoot, ClasspathCache cache) {
        Path file = classpathCacheDir.resolve(keyFor(moduleRoot) + ".json");
        try {
            writeAtomic(file, JSON.writeValueAsBytes(cache));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not write classpath cache entry " + file, e);
        }
    }

    /**
     * True when every build file {@code cache} recorded still exists with the
     * same hash. A build file that no longer exists, one whose content
     * changed, or a build-file set that no longer matches (e.g. a new parent
     * POM appeared) all count as stale; any I/O failure while re-hashing also
     * counts as stale (never trust an answer we could not verify).
     */
    public boolean isStale(ClasspathCache cache, List<Path> currentBuildFiles) {
        Map<String, String> current = new LinkedHashMap<>();
        for (Path f : currentBuildFiles) {
            Optional<String> hash = hashOf(f);
            if (hash.isEmpty()) {
                return true;
            }
            current.put(f.toAbsolutePath().normalize().toString().replace('\\', '/'), hash.get());
        }
        return !current.equals(cache.buildFileHashes());
    }

    /** SHA-256 of a build file's bytes, empty when it cannot be read. */
    public static Optional<String> hashOf(Path file) {
        try {
            byte[] content = Files.readAllBytes(file);
            return Optional.of(Hashing.sha256(content));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static void writeAtomic(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
