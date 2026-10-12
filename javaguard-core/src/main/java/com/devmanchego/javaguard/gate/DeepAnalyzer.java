package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.project.ReactorModules;
import com.devmanchego.javaguard.state.JavaGuardPaths;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Extension point for the "deep analysis" step of the end-of-turn gate (specification &sect;10.3 step 5):
 * SpotBugs/FindSecBugs, dependency checks, API compatibility and architecture rules. The gate runs the
 * registered analyzers only after a successful compile and while budget remains. API compatibility
 * ({@link ApiCompatibilityAnalyzer}) is the first; Phase 18 adds the rest.
 */
@FunctionalInterface
public interface DeepAnalyzer {

    /**
     * What an analyzer may look at.
     *
     * @param changedFiles absolute paths of the Java and build files changed in the turn (deleted ones included)
     * @param reactor      the project's modules
     * @param remaining    time left in the gate's budget
     * @param baselineOf   the file's content before the session touched it (ledger baseline, else git HEAD);
     *                     empty for a file that did not exist
     */
    record Context(List<Path> changedFiles, ReactorModules reactor, Duration remaining,
                   Function<Path, Optional<String>> baselineOf, JavaGuardConfig config, JavaGuardPaths paths) {
        public Context {
            changedFiles = List.copyOf(changedFiles);
        }
    }

    List<Finding> analyze(Context context);
}
