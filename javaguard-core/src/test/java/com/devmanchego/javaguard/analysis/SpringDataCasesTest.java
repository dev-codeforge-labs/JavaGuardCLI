package com.devmanchego.javaguard.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 22, fast half: JavaGuard's column of {@code spring-data-cases.txt}, on the generated sources of the oracle
 * fixture, without running Maven. {@link SpringDataOracleIntegrationTest} checks the oracle column against the real
 * Spring Data.
 */
class SpringDataCasesTest {

    @TempDir
    Path project;

    @Test
    void javaGuardGivesTheExpectedVerdictOnEveryCase() throws Exception {
        List<SpringDataCases.Case> cases = SpringDataCases.load();
        SpringDataCases.materialize(project, cases);
        EntityIndex index = EntityIndex.scanning(List.of(project.resolve("src/main/java")));

        List<String> wrong = new ArrayList<>();
        for (SpringDataCases.Case c : cases) {
            String verdict = SpringDataCases.javaGuard(project, c, index);
            if (!verdict.equals(c.javaGuard())) {
                wrong.add(c.describe() + ": expected " + c.javaGuard() + ", got " + verdict + " "
                        + SpringDataCases.findings(project, c, index));
            }
            assertThat(c.javaGuard().equals("FINDING") && c.oracle().equals("VALID"))
                    .as("the table itself must never expect a finding on a valid query: %s", c.describe()).isFalse();
        }

        assertThat(cases).hasSizeGreaterThan(60);
        assertThat(wrong).as("cases where JavaGuard disagrees with the table").isEmpty();
    }
}
