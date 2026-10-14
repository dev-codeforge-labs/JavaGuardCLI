package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.change.GitClient;
import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 23: migrations and configuration files through the fast gate, as POST_EDIT and {@code check} see them. */
class MigrationAndConfigGateTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC);
    private static final String V1 = "src/main/resources/db/migration/V1__init.sql";
    private static final String SQL = "create table customer (id bigint primary key);\n";

    @TempDir
    Path project;

    @TempDir
    Path home;

    private JavaGuardPaths paths;
    private JavaGuardConfig config;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>a</groupId>"
                + "<artifactId>a</artifactId><version>1</version></project>");
        paths = new JavaGuardPaths(project);
        paths.ensureLayout();
        // A slow shared runner must not exhaust the 3 s fast-gate budget and turn a finding into a pass.
        config = ConfigLoader.load(ConfigSources.standard(project, Optional.empty(), home,
                Map.of("JAVAGUARD_FASTGATE_BUDGETMILLIS", "120000"))).config();
    }

    private Path write(String relative, String content) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private GateResult check(GitClient git, String relative, String baseline) {
        FastGatePipeline pipeline = new FastGatePipeline(paths, config, CLOCK, Duration.ofSeconds(30), git);
        return pipeline.run(GateKind.POST_EDIT, List.of(new FastGatePipeline.FileUnderCheck(project.resolve(relative), relative, baseline)),
                List.of());
    }

    private static GitClient headHas(String content) {
        return file -> Optional.of(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void editingACommittedMigrationIsBlocked() throws Exception {
        Files.createDirectories(project.resolve(".git"));
        write(V1, SQL.replace("bigint", "int"));

        GateResult result = check(headHas(SQL), V1, SQL);

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("JG-DB-001");
            assertThat(f.line()).isEqualTo(1);
            assertThat(f.message()).contains("V1__init.sql").contains("checksum");
        });
    }

    @Test
    void aMigrationThatOnlyExistsInTheWorkingTreeMayBeEdited() throws Exception {
        Files.createDirectories(project.resolve(".git"));
        write(V1, SQL.replace("bigint", "int"));

        GateResult result = check(file -> Optional.empty(), V1, SQL);

        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void withoutGitAnExistingMigrationCountsAsApplied() throws Exception {
        write(V1, SQL.replace("bigint", "int"));

        GateResult result = check(file -> Optional.empty(), V1, SQL);

        assertThat(result.blocking()).extracting(Finding::ruleId).containsExactly("JG-DB-001");
    }

    @Test
    void aNewMigrationReusingAVersionIsReportedAsCritical() throws Exception {
        write(V1, SQL);
        String duplicate = "src/main/resources/db/migration/V1.0__customer_email.sql";
        write(duplicate, "alter table customer add column email varchar(100);\n");

        GateResult result = check(headHas(""), duplicate, "");

        assertThat(result.verdict()).as("CRITICAL advises at POST_EDIT and blocks at END_OF_TURN").isEqualTo(Verdict.ADVISE);
        assertThat(result.advisory()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("JG-DB-003");
            assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(f.message()).contains("V1__init.sql");
        });
    }

    @Test
    void aJavaMigrationIsCheckedAsAMigrationAndAsJava() throws Exception {
        String rel = "src/main/java/db/migration/V2__Backfill.java";
        String before = "package db.migration;\npublic class V2__Backfill {\n    void migrate() {\n    }\n}\n";
        write(rel, before.replace("    void migrate() {\n", "    void migrate() {\n        System.out.println(\"done\");\n"));

        GateResult result = check(headHas(before), rel, before);

        assertThat(result.advisory()).extracting(Finding::ruleId).contains("JG-DB-001", "JG-AI-001");
        assertThat(result.advisory()).filteredOn(f -> f.ruleId().equals("JG-DB-001")).singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.MAJOR));
    }

    @Test
    void aSecretNestedInYamlIsBlockedAtItsLine() throws Exception {
        String rel = "src/main/resources/application.yml";
        String before = "spring:\n  datasource:\n    url: jdbc:postgresql://db/app\n";
        write(rel, before + "    username: app\n    password: Pr0d-Passw0rd!\n");

        GateResult result = check(headHas(before), rel, before);

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("JG-CFG-001");
            assertThat(f.line()).isEqualTo(5);
            assertThat(f.message()).contains("spring.datasource.password");
            assertThat(f.snippet()).isEmpty();
        });
    }

    @Test
    void aSecretInADevelopmentProfileIsOnlyAdvised() throws Exception {
        String rel = "src/main/resources/application-dev.properties";
        write(rel, "spring.datasource.password=devpassword1\n");

        GateResult result = check(headHas(""), rel, "");

        assertThat(result.verdict()).isEqualTo(Verdict.ADVISE);
        assertThat(result.advisory()).extracting(Finding::severity).containsExactly(Severity.MAJOR);
    }
}
