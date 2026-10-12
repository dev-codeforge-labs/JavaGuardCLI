package com.devmanchego.javaguard.handoff;

import com.devmanchego.javaguard.state.JavaGuardPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A second model's proposal waiting for the agent (specification &sect;14.4). In order, JavaGuard looks for
 * {@code .javaguard/handoff/SOLUTION-<session-short>.md} and then {@code AI_SOLUTION.md} in the project root. A file
 * qualifies when it is newer than the session's latest handoff (any file when there is no handoff) and has not been
 * consumed; consumption is a {@code <file>.consumed} sidecar at least as new as the file, so editing the file again
 * makes it a new solution.
 */
public final class SolutionPickup {

    /** A solution to deliver. */
    public record Solution(Path file, String text) {
    }

    private SolutionPickup() {
    }

    public static Optional<Solution> find(JavaGuardPaths paths, String sessionId) {
        String shortId = shortId(sessionId);
        FileTime latestHandoff = latestHandoff(paths, shortId);
        for (Path candidate : List.of(paths.handoffDir().resolve("SOLUTION-" + shortId + ".md"),
                paths.projectRoot().resolve("AI_SOLUTION.md"))) {
            try {
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                FileTime modified = Files.getLastModifiedTime(candidate);
                if (latestHandoff != null && modified.compareTo(latestHandoff) <= 0) {
                    continue;
                }
                if (consumed(candidate, modified)) {
                    continue;
                }
                String text = Files.readString(candidate, StandardCharsets.UTF_8);
                if (!text.isBlank()) {
                    return Optional.of(new Solution(candidate, text));
                }
            } catch (IOException e) {
                // An unreadable candidate is skipped; the next one may do.
            }
        }
        return Optional.empty();
    }

    public static void markConsumed(Solution solution) throws IOException {
        Path sidecar = sidecar(solution.file());
        Files.writeString(sidecar, "consumed\n", StandardCharsets.UTF_8);
        // The sidecar must not look older than the file it covers, even on coarse file-system clocks.
        Files.setLastModifiedTime(sidecar, FileTime.fromMillis(Math.max(System.currentTimeMillis(),
                Files.getLastModifiedTime(solution.file()).toMillis() + 1)));
    }

    /** First 8 characters of the sanitized session id, as in the file names. */
    public static String shortId(String sessionId) {
        String clean = sessionId.replaceAll("[^A-Za-z0-9_-]", "_");
        return clean.length() <= 8 ? clean : clean.substring(0, 8);
    }

    private static boolean consumed(Path file, FileTime modified) throws IOException {
        Path sidecar = sidecar(file);
        return Files.isRegularFile(sidecar) && Files.getLastModifiedTime(sidecar).compareTo(modified) >= 0;
    }

    private static Path sidecar(Path file) {
        return file.resolveSibling(file.getFileName() + ".consumed");
    }

    private static FileTime latestHandoff(JavaGuardPaths paths, String shortId) {
        if (!Files.isDirectory(paths.handoffDir())) {
            return null;
        }
        try (Stream<Path> files = Files.list(paths.handoffDir())) {
            return files.filter(p -> p.getFileName().toString().startsWith("HANDOFF-" + shortId + "-"))
                    .map(p -> {
                        try {
                            return Files.getLastModifiedTime(p);
                        } catch (IOException e) {
                            return FileTime.fromMillis(0);
                        }
                    }).max(FileTime::compareTo).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
