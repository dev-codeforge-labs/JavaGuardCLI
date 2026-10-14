package com.devmanchego.javaguard.project;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Path comparisons that survive the way operating systems spell the same place differently: {@code /var} and
 * {@code /private/var} on macOS, {@code RUNNER~1} and {@code runneradmin} on Windows (8.3 short names), a project reached
 * through a symbolic link. Build tools print the real, long form; the project root is whatever the agent or the caller said.
 * Comparing the two textually made a compile error at {@code /private/var/.../Bar.java} look like it was outside the project.
 */
public final class ProjectPaths {

    private ProjectPaths() {
    }

    /**
     * {@code file} relative to {@code root}, or empty when it is not under it. Tried as given first, then with symbolic links
     * and short names resolved on both sides (the file need not exist: its nearest existing ancestor is resolved).
     */
    public static Optional<Path> relativeTo(Path root, Path file) {
        Path plainRoot = root.toAbsolutePath().normalize();
        Path plainFile = file.toAbsolutePath().normalize();
        if (plainFile.startsWith(plainRoot) && !plainFile.equals(plainRoot)) {
            return Optional.of(plainRoot.relativize(plainFile));
        }
        Path realRoot = real(plainRoot);
        Path realFile = real(plainFile);
        if (realFile.startsWith(realRoot) && !realFile.equals(realRoot)) {
            return Optional.of(realRoot.relativize(realFile));
        }
        return Optional.empty();
    }

    /** Whether {@code file} is {@code root} or lies under it, by either spelling. */
    public static boolean isInside(Path root, Path file) {
        Path plainRoot = root.toAbsolutePath().normalize();
        Path plainFile = file.toAbsolutePath().normalize();
        return plainFile.startsWith(plainRoot) || real(plainFile).startsWith(real(plainRoot));
    }

    /** The real path of {@code path}, or of its nearest existing ancestor with the missing tail appended. */
    static Path real(Path path) {
        Path existing = path;
        Path tail = null;
        while (existing != null) {
            try {
                Path resolved = existing.toRealPath();
                return tail == null ? resolved : resolved.resolve(tail);
            } catch (IOException | RuntimeException e) {
                Path name = existing.getFileName();
                if (name == null) {
                    break;
                }
                tail = tail == null ? name : name.resolve(tail);
                existing = existing.getParent();
            }
        }
        return path;
    }
}
