package com.devmanchego.javaguard.build;

import com.devmanchego.javaguard.testing.TestSelection;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;

/**
 * Drives a build tool for the end-of-turn gate (specification &sect;5.2, &sect;10.3). The Maven
 * implementation is {@code MavenBuildRunner}, the Gradle one {@code GradleBuildRunner}.
 */
public interface BuildRunner {

    /**
     * Compiles main and test sources of {@code moduleRoots} and everything that depends on them
     * (specification &sect;10.3 step 2).
     *
     * @param moduleRoots modules containing the changed files; empty means the whole reactor
     */
    BuildResult testCompile(Collection<Path> moduleRoots, Duration timeout);

    /**
     * Runs the selected tests and reports failures (specification &sect;10.3 steps 3-4). Compiles first,
     * so it can also report compile errors.
     */
    BuildResult runTests(TestSelection selection, Duration timeout);

    /** Outcome of {@link #compileAndTest}: the compile, and the tests when the compile succeeded. */
    record CompileAndTest(BuildResult compile, java.util.Optional<BuildResult> tests) {
    }

    /** Whether {@link #compileAndTest} can do both steps in one build-tool invocation for this selection. */
    default boolean canCombine(TestSelection selection) {
        return false;
    }

    /**
     * Compiles {@code moduleRoots} with their dependents and runs {@code selection} in a single invocation (one build-tool
     * start instead of two). Only called when {@link #canCombine} says so.
     */
    default CompileAndTest compileAndTest(Collection<Path> moduleRoots, TestSelection selection, Duration timeout) {
        throw new UnsupportedOperationException("compileAndTest");
    }
}
