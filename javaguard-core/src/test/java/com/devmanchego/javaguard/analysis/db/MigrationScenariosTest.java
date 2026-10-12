package com.devmanchego.javaguard.analysis.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 23, fast half: JavaGuard's column of {@link MigrationScenarios}; {@link MigrationOracleTest} checks the tools'. */
class MigrationScenariosTest {

    @TempDir
    Path workspace;

    @Test
    void javaGuardGivesTheExpectedVerdictOnEveryScenario() throws Exception {
        List<String> wrong = new ArrayList<>();
        int n = 0;
        for (MigrationScenarios.Scenario scenario : MigrationScenarios.all()) {
            Path root = Files.createDirectories(workspace.resolve("s" + n++));
            MigrationScenarios.write(root, scenario.before());
            MigrationScenarios.write(root, scenario.after());
            String verdict = MigrationScenarios.javaGuard(root, scenario);
            if (!verdict.equals(scenario.javaGuard())) {
                wrong.add(scenario.name() + ": expected " + scenario.javaGuard() + ", got " + verdict + " "
                        + MigrationScenarios.findings(root, scenario));
            }
            assertThat(scenario.javaGuard().equals("BLOCKING") && scenario.oracle().equals("OK"))
                    .as("the table must never expect a block on an edit the tool accepts: %s", scenario.name()).isFalse();
        }

        assertThat(wrong).as("scenarios where JavaGuard disagrees with the table").isEmpty();
    }
}
