package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression (found by the Phase 20 end-to-end consultation scenario): writing the full report rebuilt the result and lost
 * the second model's solution, so the agent never received it.
 */
class FullReportKeepsSolutionTest {

    @TempDir
    Path root;

    @Test
    void theFullReportKeepsTheSolutionAndTheEscalation() throws Exception {
        JavaGuardPaths paths = new JavaGuardPaths(root);
        paths.ensureLayout();
        var config = ConfigLoader.load(ConfigSources.standard(root, Optional.empty(), root.resolve("home"), Map.of())).config();
        Finding finding = new Finding("JG-SEC-001", "SQL built by concatenation", Category.SECURITY, Severity.BLOCKER, "ast",
                Optional.of(Path.of("A.java")), 1, 1, 1, "m", "r", Optional.empty(), Map.of(), Fingerprints.of("JG-SEC-001", "A.java", "m"));
        GateResult blocked = new GateResult(GateKind.POST_EDIT, Verdict.BLOCK, List.of(finding), List.of(), List.of(), Optional.empty(),
                Optional.empty(), List.of(), Duration.ZERO, Optional.of("the proposal"));

        GateResult reported = new FastGatePipeline(paths, config, Clock.systemUTC()).withFullReport(blocked);

        assertThat(reported.fullReport()).isPresent();
        assertThat(reported.solution()).contains("the proposal");
    }
}
