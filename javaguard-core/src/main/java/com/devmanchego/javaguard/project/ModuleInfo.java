package com.devmanchego.javaguard.project;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Everything the analysis pipeline needs to know about the module a file
 * belongs to (specification &sect;7).
 *
 * @param moduleRoot     deepest ancestor directory holding a build file
 * @param reactorRoot    top-most directory of the same reactor/build (equals
 *                       {@code moduleRoot} for a single-module project)
 * @param buildTool      Maven or Gradle
 * @param mainSourceRoots absolute source roots for main code
 * @param testSourceRoots absolute source roots for test code
 * @param javaRelease    the {@code --release} value to compile with
 * @param frameworks     frameworks detected as actually declared dependencies
 */
public record ModuleInfo(
        Path moduleRoot,
        Path reactorRoot,
        BuildTool buildTool,
        List<Path> mainSourceRoots,
        List<Path> testSourceRoots,
        int javaRelease,
        Set<Framework> frameworks
) {

    public ModuleInfo {
        Objects.requireNonNull(moduleRoot, "moduleRoot");
        Objects.requireNonNull(reactorRoot, "reactorRoot");
        Objects.requireNonNull(buildTool, "buildTool");
        mainSourceRoots = List.copyOf(mainSourceRoots);
        testSourceRoots = List.copyOf(testSourceRoots);
        frameworks = Set.copyOf(frameworks);
        if (javaRelease < 1) {
            throw new IllegalArgumentException("javaRelease must be positive: " + javaRelease);
        }
    }

    /** True when {@code path} falls under one of {@link #mainSourceRoots()}. */
    public boolean isMainSource(Path path) {
        return mainSourceRoots.stream().anyMatch(path::startsWith);
    }

    /** True when {@code path} falls under one of {@link #testSourceRoots()}. */
    public boolean isTestSource(Path path) {
        return testSourceRoots.stream().anyMatch(path::startsWith);
    }
}
