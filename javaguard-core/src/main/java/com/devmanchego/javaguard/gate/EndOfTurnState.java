package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.change.Hashing;
import com.devmanchego.javaguard.change.SessionLock;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What the end-of-turn gate remembers about a session between runs
 * ({@code .javaguard/state/<session>/endofturn.json}).
 *
 * @param knownHashes        content hash of every relevant file as of the last time it was accounted for
 *                           (the sentinel {@value #ABSENT} for a deleted file). A file whose current hash
 *                           differs, or that is not listed, changed since then. Seeded by the first
 *                           POST_EDIT of the session with the uncommitted files that were already there, so
 *                           work in progress the agent did not touch is never blamed on it.
 * @param pendingFiles       files of the last blocked run: they stay in the changed set until a run passes,
 *                           so a fix elsewhere cannot hide a test that is still failing
 * @param consecutiveBlocks  END_OF_TURN runs in a row that blocked without reducing the blocking count
 * @param lastBlockingCount  blocking findings of the last blocked run
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EndOfTurnState(int version, String lastRun, Map<String, String> knownHashes, List<String> pendingFiles,
                             int consecutiveBlocks, int lastBlockingCount) {

    public static final int CURRENT_VERSION = 1;
    /** Hash recorded for a file that does not exist. */
    public static final String ABSENT = "-";

    public EndOfTurnState {
        knownHashes = knownHashes == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(knownHashes));
        pendingFiles = pendingFiles == null ? List.of() : List.copyOf(pendingFiles);
    }

    public static EndOfTurnState seeded(Map<String, String> knownHashes) {
        return new EndOfTurnState(CURRENT_VERSION, "", knownHashes, List.of(), 0, 0);
    }

    /** SHA-256 of the file, or {@link #ABSENT}. */
    public static String hashOf(Path file) {
        try {
            return Hashing.sha256(Files.readAllBytes(file));
        } catch (IOException e) {
            return ABSENT;
        }
    }

    /** Reads and writes the state under the session lock; a lock timeout degrades to "no state". */
    public static final class Store {

        private static final Logger LOG = Logger.getLogger(Store.class.getName());
        private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(2);
        private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        private final JavaGuardPaths paths;

        public Store(JavaGuardPaths paths) {
            this.paths = paths;
        }

        public Optional<EndOfTurnState> load(String sessionId) {
            try (SessionLock ignored = lock(sessionId)) {
                Path file = file(sessionId);
                if (!Files.isRegularFile(file)) {
                    return Optional.empty();
                }
                return Optional.of(JSON.readValue(file.toFile(), EndOfTurnState.class));
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not read the end-of-turn state of session " + sessionId, e);
                return Optional.empty();
            }
        }

        public void save(String sessionId, EndOfTurnState state) {
            try (SessionLock ignored = lock(sessionId)) {
                write(file(sessionId), JSON.writeValueAsBytes(state));
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not save the end-of-turn state of session " + sessionId, e);
            }
        }

        /** Stores {@code state} only when the session has none yet. Returns whether it did. */
        public boolean saveIfAbsent(String sessionId, EndOfTurnState state) {
            try (SessionLock ignored = lock(sessionId)) {
                Path file = file(sessionId);
                if (Files.exists(file)) {
                    return false;
                }
                write(file, JSON.writeValueAsBytes(state));
                return true;
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not seed the end-of-turn state of session " + sessionId, e);
                return false;
            }
        }

        private Path file(String sessionId) {
            return paths.sessionDir(sessionId).resolve("endofturn.json");
        }

        private SessionLock lock(String sessionId) throws IOException {
            return SessionLock.acquire(paths.sessionDir(sessionId).resolve(".lock"), LOCK_TIMEOUT);
        }

        private static void write(Path target, byte[] content) throws IOException {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "endofturn", ".tmp");
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
}
