package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.project.BuildToolExecutable;
import com.devmanchego.javaguard.process.ProcessResult;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.project.BuildToolLocator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 22: every case of {@code spring-data-cases.txt} is judged by the real Spring Data JPA 3.3.4 and Hibernate 6.5
 * (fixture {@code spring-data-oracle}, built with Maven: it downloads its dependencies on the first run). The oracle must
 * agree with the table, and JavaGuard must never report a query the oracle accepts.
 */
@Tag("integration")
class SpringDataOracleIntegrationTest {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    @TempDir
    Path project;

    @Test
    void theRealSpringDataAgreesWithTheTableAndNeverAcceptsWhatJavaGuardReports() throws Exception {
        List<SpringDataCases.Case> cases = SpringDataCases.load();
        SpringDataCases.materialize(project, cases);
        BuildToolExecutable mvn = BuildToolLocator.maven(project, false, System.getenv(), WINDOWS).orElseThrow();
        List<String> command = new ArrayList<>(mvn.command());
        command.addAll(List.of("-q", "-B", "test"));

        ProcessResult build = new ProcessRunner().run(command, project, Map.of(), Duration.ofMinutes(10));

        assertThat(build.succeeded()).as("oracle build output:%n%s", build.output()).isTrue();
        Path resultsFile = project.resolve("target/oracle-results.txt");
        Files.copy(resultsFile, Path.of("target/spring-data-oracle-results.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Map<String, String[]> results = new HashMap<>();
        for (String line : Files.readAllLines(resultsFile, StandardCharsets.UTF_8)) {
            String[] f = line.split("\\|", 3);
            results.put(f[0], f);
        }
        EntityIndex index = EntityIndex.scanning(List.of(project.resolve("src/main/java")));
        List<String> disagreements = new ArrayList<>();
        List<String> falsePositives = new ArrayList<>();
        for (SpringDataCases.Case c : cases) {
            String[] result = results.get(c.name());
            String oracle = result == null ? "MISSING" : result[1];
            String why = result != null && result.length > 2 ? " (" + result[2] + ")" : "";
            if (!oracle.equals(c.oracle())) {
                disagreements.add(c.describe() + ": table says " + c.oracle() + ", Spring Data says " + oracle + why);
            }
            if (SpringDataCases.javaGuard(project, c, index).equals("FINDING") && oracle.equals("VALID")) {
                falsePositives.add(c.describe());
            }
        }

        assertThat(falsePositives).as("JavaGuard reports queries that Spring Data accepts").isEmpty();
        assertThat(disagreements).as("the table disagrees with the real Spring Data").isEmpty();
    }
}
