package com.devmanchego.javaguard.project;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves the Maven/Gradle executable to invoke (specification &sect;7
 * rule 3): the reactor's own wrapper first, then (Maven only, when enabled)
 * a daemon on {@code PATH}, then the plain executable on {@code PATH}.
 *
 * <p>On Windows, {@code *.cmd}/{@code *.bat} scripts are always launched
 * explicitly by absolute path, never through a shell string, and are matched
 * against {@code PATHEXT} like the OS itself would.
 */
public final class BuildToolLocator {

    private static final Pattern SPLIT = Pattern.compile(Pattern.quote(File.pathSeparator));
    /** {@code PATHEXT} is a Windows variable and always uses ';', whatever the platform running the lookup. */
    private static final Pattern PATHEXT_SPLIT = Pattern.compile(";");

    private BuildToolLocator() {
    }

    public static Optional<BuildToolExecutable> maven(Path reactorRoot, boolean useDaemon, Map<String, String> env,
                                                       boolean windows) {
        Optional<Path> wrapper = wrapper(reactorRoot, "mvnw", windows ? ".cmd" : "");
        if (wrapper.isPresent()) {
            return Optional.of(new BuildToolExecutable(List.of(wrapper.get().toString()), "wrapper"));
        }
        if (useDaemon) {
            Optional<Path> mvnd = findOnPath("mvnd", env, windows);
            if (mvnd.isPresent()) {
                return Optional.of(new BuildToolExecutable(List.of(mvnd.get().toString()), "daemon"));
            }
        }
        return findOnPath("mvn", env, windows).map(p -> new BuildToolExecutable(List.of(p.toString()), "path"));
    }

    public static Optional<BuildToolExecutable> gradle(Path reactorRoot, Map<String, String> env, boolean windows) {
        // Gradle's own wrapper generator names the Windows script ".bat", unlike Maven's ".cmd".
        Optional<Path> wrapper = wrapper(reactorRoot, "gradlew", windows ? ".bat" : "");
        if (wrapper.isPresent()) {
            return Optional.of(new BuildToolExecutable(List.of(wrapper.get().toString()), "wrapper"));
        }
        return findOnPath("gradle", env, windows).map(p -> new BuildToolExecutable(List.of(p.toString()), "path"));
    }

    private static Optional<Path> wrapper(Path reactorRoot, String baseName, String extension) {
        Path candidate = reactorRoot.resolve(baseName + extension);
        if (Files.isRegularFile(candidate)) {
            return Optional.of(candidate.toAbsolutePath().normalize());
        }
        return Optional.empty();
    }

    public static Optional<Path> findOnPath(String name, Map<String, String> env, boolean windows) {
        String pathVar = getCaseInsensitive(env, "PATH");
        if (pathVar == null || pathVar.isBlank()) {
            return Optional.empty();
        }
        List<String> suffixes = windows ? pathExtensions(env, name) : List.of("");
        for (String dir : SPLIT.split(pathVar, -1)) {
            if (dir.isBlank()) {
                continue;
            }
            Path dirPath = Path.of(dir);
            for (String suffix : suffixes) {
                Path candidate = dirPath.resolve(name + suffix);
                if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
                    return Optional.of(candidate.toAbsolutePath().normalize());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * PATHEXT-driven suffixes to try after {@code name}, in the order real
     * Windows command resolution does: a bare {@code name} (no suffix) is
     * only tried when {@code name} already carries its own extension (a
     * caller-given {@code "foo.exe"} should still match as-is); for an
     * extension-less base name like {@code "mvn"}, only the PATHEXT
     * extensions are tried, exactly like {@code cmd.exe} resolving a typed
     * command - never the bare name. Skipping this distinction is a real bug:
     * a Maven distribution's {@code bin/} ships both {@code mvn} (the Unix
     * shell launcher, not a native Windows executable) and {@code mvn.cmd}
     * side by side, and trying the bare name first would hand back the
     * former, which {@code CreateProcess} then refuses to launch.
     */
    private static List<String> pathExtensions(Map<String, String> env, String name) {
        String pathext = getCaseInsensitive(env, "PATHEXT");
        if (pathext == null || pathext.isBlank()) {
            pathext = ".COM;.EXE;.BAT;.CMD";
        }
        List<String> suffixes = new ArrayList<>();
        if (name.contains(".")) {
            suffixes.add("");
        }
        for (String ext : PATHEXT_SPLIT.split(pathext, -1)) {
            if (!ext.isBlank()) {
                suffixes.add(ext);
            }
        }
        return suffixes;
    }

    /** Windows environment variable names are case-insensitive; a plain {@code Map} lookup is not. */
    private static String getCaseInsensitive(Map<String, String> env, String name) {
        String exact = env.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }
}
