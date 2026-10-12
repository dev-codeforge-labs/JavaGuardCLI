package com.devmanchego.javaguard.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads, tolerantly and by text, the two things about a Gradle build that JavaGuard needs and that Gradle only gives
 * by running it: which sub-projects {@code settings.gradle(.kts)} includes, and which sibling projects a module's build
 * file depends on. Both DSLs are matched with the same patterns because {@code include("a")} and
 * {@code include 'a'} differ only in punctuation. What is not matched (programmatic includes, type-safe
 * {@code projects.foo} accessors, a remapped {@code projectDir}) is simply not seen; the consequence is a module the
 * end-of-turn gate does not build separately, never a wrong result (specification &sect;7, Gradle decisions).
 */
public final class GradleProjects {

    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\s*include(?:Flat)?\\b\\s*\\(?((?:\\s*[\"'][^\"']+[\"']\\s*,?)+)");
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");
    private static final Pattern PROJECT_DEPENDENCY = Pattern.compile("project\\(\\s*(?:path\\s*[:=]\\s*)?[\"']([^\"']+)[\"']");

    private GradleProjects() {
    }

    /** The settings file of {@code root}, if any. */
    public static Optional<Path> settingsFile(Path root) {
        for (String name : List.of("settings.gradle.kts", "settings.gradle")) {
            if (Files.isRegularFile(root.resolve(name))) {
                return Optional.of(root.resolve(name));
            }
        }
        return Optional.empty();
    }

    /** Project paths without the leading colon, in declaration order: {@code include(":a", "b:c")} gives {@code a}, {@code b:c}. */
    public static List<String> includedProjects(Path root) {
        Optional<Path> settings = settingsFile(root);
        if (settings.isEmpty()) {
            return List.of();
        }
        Set<String> projects = new LinkedHashSet<>();
        Matcher include = INCLUDE.matcher(stripComments(read(settings.get())));
        while (include.find()) {
            Matcher name = QUOTED.matcher(include.group(1));
            while (name.find()) {
                String path = name.group(1).strip();
                if (path.startsWith(":")) {
                    path = path.substring(1);
                }
                if (!path.isEmpty()) {
                    projects.add(path);
                }
            }
        }
        return new ArrayList<>(projects);
    }

    /** The directory of project {@code path} ({@code a:b} is {@code a/b}) under {@code root}. */
    public static Path directoryOf(Path root, String path) {
        return root.resolve(path.replace(':', '/')).normalize();
    }

    /** Project paths (no leading colon) that the build file of {@code moduleRoot} depends on through {@code project(":x")}. */
    public static Set<String> projectDependencies(Path moduleRoot) {
        Set<String> out = new LinkedHashSet<>();
        for (String name : List.of("build.gradle.kts", "build.gradle")) {
            Path file = moduleRoot.resolve(name);
            if (Files.isRegularFile(file)) {
                Matcher m = PROJECT_DEPENDENCY.matcher(stripComments(read(file)));
                while (m.find()) {
                    String path = m.group(1).strip();
                    out.add(path.startsWith(":") ? path.substring(1) : path);
                }
            }
        }
        return out;
    }

    /** The Gradle project path ({@code :a:b}, {@code :} for the root) of a module directory. */
    public static String taskPrefix(Path reactorRoot, Path moduleRoot) {
        String relative = reactorRoot.relativize(moduleRoot.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return relative.isEmpty() ? ":" : ":" + relative.replace('/', ':') + ":";
    }

    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", "");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
