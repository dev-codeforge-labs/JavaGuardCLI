package com.devmanchego.javaguard.adapter;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns path strings found in agent payloads into absolute, normalized
 * {@link Path}s.
 *
 * <p>On Windows, agents running under Git Bash / MSYS / Cygwin may report
 * POSIX-style drive paths ({@code /c/work/x}, {@code /cygdrive/c/work/x});
 * these are converted to {@code C:/work/x} before resolution. On other
 * operating systems strings are used unchanged.
 */
public final class PathNormalizer {

    private static final Pattern MSYS_DRIVE = Pattern.compile("^/(?:cygdrive/)?([A-Za-z])(/.*)?$");

    private final boolean windows;

    public PathNormalizer(boolean windows) {
        this.windows = windows;
    }

    public static PathNormalizer forCurrentOs() {
        return new PathNormalizer(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
    }

    /** MSYS drive conversion only (string level, OS-independent for testing). */
    public String toNativeString(String raw) {
        if (!windows) {
            return raw;
        }
        Matcher m = MSYS_DRIVE.matcher(raw);
        if (!m.matches()) {
            return raw;
        }
        String rest = m.group(2) == null ? "/" : m.group(2);
        return m.group(1).toUpperCase(Locale.ROOT) + ":" + rest;
    }

    /**
     * Resolves {@code raw} against {@code base} when relative, then normalizes
     * ({@code .} and {@code ..} removed). Never touches the file system.
     *
     * @throws IllegalArgumentException when {@code raw} is blank or not a valid path
     */
    public Path resolve(String raw, Path base) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Blank path in payload");
        }
        Path path = Path.of(toNativeString(raw.strip()));
        Path absolute = path.isAbsolute() ? path : base.resolve(path);
        return absolute.normalize();
    }
}
