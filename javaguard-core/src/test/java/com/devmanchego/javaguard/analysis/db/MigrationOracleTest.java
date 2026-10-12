package com.devmanchego.javaguard.analysis.db;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 23: every {@link MigrationScenarios} case run through the real Flyway 10.10 and Liquibase 4.27 (the versions
 * Spring Boot 3.3.4 uses) on H2: migrate with the files before the edit, then again with the files after it. The oracle
 * column must match, and JavaGuard must never block an edit the tool accepts.
 */
@Tag("integration")
class MigrationOracleTest {

    @TempDir
    Path workspace;

    @Test
    void theRealToolsAgreeWithTheTableAndNeverAcceptWhatJavaGuardBlocks() throws Exception {
        List<String> disagreements = new ArrayList<>();
        List<String> falseBlocks = new ArrayList<>();
        int n = 0;
        for (MigrationScenarios.Scenario scenario : MigrationScenarios.all()) {
            Path root = Files.createDirectories(workspace.resolve("s" + n++));
            String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
            MigrationScenarios.write(root, scenario.before());
            String first = run(scenario, root, url);
            if (!first.equals("OK")) {
                disagreements.add(scenario.name() + ": the files before the edit do not migrate: " + first);
                continue;
            }
            MigrationScenarios.write(root, scenario.after());
            String second = run(scenario, root, url);
            String oracle = second.equals("OK") ? "OK" : "FAILS";
            if (!oracle.equals(scenario.oracle())) {
                disagreements.add(scenario.name() + ": table says " + scenario.oracle() + ", the tool says " + second);
            }
            if (MigrationScenarios.javaGuard(root, scenario).equals("BLOCKING") && oracle.equals("OK")) {
                falseBlocks.add(scenario.name());
            }
        }

        assertThat(falseBlocks).as("JavaGuard blocks edits the tool accepts").isEmpty();
        assertThat(disagreements).as("the table disagrees with the real tools").isEmpty();
    }

    /** "OK", or the failure's message. */
    private static String run(MigrationScenarios.Scenario scenario, Path root, String url) {
        try {
            if (scenario.tool() == MigrationScenarios.Tool.FLYWAY) {
                Flyway.configure().dataSource(url, "sa", "").locations("filesystem:" + root.resolve(MigrationScenarios.DIR))
                        .load().migrate();
            } else {
                Path dir = root.resolve(MigrationScenarios.LOG);
                String changelog;
                try (var files = Files.list(dir)) {
                    changelog = files.findFirst().orElseThrow().getFileName().toString();
                }
                try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
                    Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
                    try (Liquibase liquibase = new Liquibase(changelog, new DirectoryResourceAccessor(dir), database)) {
                        liquibase.update(new Contexts());
                    }
                }
            }
            return "OK";
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            return e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).lines().findFirst().orElse("")
                    + (cause != e ? " / " + cause.getClass().getSimpleName() : "");
        }
    }
}
