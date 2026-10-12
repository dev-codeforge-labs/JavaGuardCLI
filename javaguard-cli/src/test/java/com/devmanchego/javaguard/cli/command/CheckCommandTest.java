package com.devmanchego.javaguard.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 7: the manual {@code check} command (specification &sect;4, &sect;17). */
class CheckCommandTest extends CommandTestBase {

    private static final String REL = "src/main/java/com/acme/App.java";

    private static final String SQL_CONCAT = """
            package com.acme;

            import java.sql.Connection;
            import java.sql.PreparedStatement;
            import java.sql.SQLException;

            public class App {
                PreparedStatement run(Connection conn, String id) throws SQLException {
                    return conn.prepareStatement("SELECT * FROM t WHERE id = " + id);
                }
            }
            """;

    private static final String CLEAN = """
            package com.acme;

            public class App {
                public int one() {
                    return 1;
                }
            }
            """;

    @BeforeEach
    void project() throws IOException {
        createMavenProject();
    }

    @Test
    void aBlockingFindingGivesExitCodeOneAndTheReportOnStdout() throws Exception {
        Path file = writeProjectFile(REL, SQL_CONCAT);

        int exit = run("check", file.toString());

        assertThat(exit).isEqualTo(1);
        assertThat(stdout()).startsWith("JAVAGUARD BLOCK").contains("JG-SEC-001");
    }

    @Test
    void aCleanFileGivesExitCodeZero() throws Exception {
        Path file = writeProjectFile(REL, CLEAN);

        int exit = run("check", file.toString());

        assertThat(exit).isZero();
        assertThat(stdout()).startsWith("JAVAGUARD PASS");
    }

    @Test
    void explicitFilesAreAnalyzedInFullIncludingLegacyDebt() throws Exception {
        Path file = writeProjectFile(REL, """
                package com.acme;

                public class App {
                    public int one() {
                        System.out.println("legacy");
                        return 1;
                    }
                }
                """);

        run("check", file.toString());

        assertThat(stdout()).contains("JG-AI-001");
    }

    @Test
    void directoriesAreWalkedForJavaFiles() throws Exception {
        writeProjectFile(REL, SQL_CONCAT);

        int exit = run("check", projectRoot.resolve("src").toString());

        assertThat(exit).isEqualTo(1);
    }

    @Test
    void jsonFormatIsParseableAndCarriesTheVerdict() throws Exception {
        Path file = writeProjectFile(REL, SQL_CONCAT);

        int exit = run("check", "--format", "json", file.toString());

        assertThat(exit).isEqualTo(1);
        JsonNode json = new ObjectMapper().readTree(stdout());
        assertThat(json.toString()).contains("BLOCK").contains("JG-SEC-001");
    }

    @Test
    void sarifFormatIsParseableSarif() throws Exception {
        Path file = writeProjectFile(REL, SQL_CONCAT);

        run("check", "--format", "sarif", file.toString());

        JsonNode sarif = new ObjectMapper().readTree(stdout());
        assertThat(sarif.get("version").asText()).isEqualTo("2.1.0");
        assertThat(sarif.at("/runs/0/results")).isNotEmpty();
    }

    @Test
    void markdownFormatIsTheHumanReport() throws Exception {
        Path file = writeProjectFile(REL, SQL_CONCAT);

        run("check", "--format", "md", file.toString());

        assertThat(stdout()).startsWith("#");
    }

    @Test
    void outputWritesTheReportToAFileInsteadOfStdout() throws Exception {
        Path file = writeProjectFile(REL, SQL_CONCAT);
        Path report = projectRoot.resolve("out/report.txt");

        int exit = run("check", "--output", report.toString(), file.toString());

        assertThat(exit).isEqualTo(1);
        assertThat(stdout()).isEmpty();
        assertThat(Files.readString(report, StandardCharsets.UTF_8)).contains("JG-SEC-001");
    }

    @Test
    void unknownFormatIsAUsageError() {
        assertThat(run("check", "--format", "xml")).isEqualTo(2);
    }

    @Test
    void anInvalidConfigurationIsAnErrorInManualMode() throws Exception {
        Files.writeString(projectRoot.resolve("javaguard.yml"), "fastGate: { budgetMillis: 0 }\n");
        Path file = writeProjectFile(REL, CLEAN);

        int exit = run("check", file.toString());

        assertThat(exit).isEqualTo(3);
        assertThat(stderr()).contains("fastGate.budgetMillis");
    }

    @Test
    void withoutPathsOnlyWhatChangedAgainstHeadIsReported() throws Exception {
        String legacy = """
                package com.acme;

                import java.io.FileInputStream;
                import java.io.IOException;

                public class App {
                    public void legacy(String path) {
                        try {
                            FileInputStream in = new FileInputStream(path);
                            in.read();
                        } catch (IOException e) {
                            // pre-existing: swallowed
                        }
                    }

                    public int one() {
                        return 1;
                    }
                }
                """;
        Path file = writeProjectFile(REL, legacy);
        git("init", "-q");
        git("add", ".");
        git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "legacy");
        Files.writeString(file, legacy.replace("return 1;", "System.out.println(\"debug\");\n        return 1;"));

        int exit = run("check");

        assertThat(exit).isZero();
        assertThat(stdout()).contains("JG-AI-001").doesNotContain("JG-REL-001").doesNotContain("EmptyCatchBlock");
    }

    @Test
    void withoutPathsAndWithoutAGitRepositoryTheUserIsTold() {
        int exit = run("check");

        assertThat(exit).isEqualTo(4);
        assertThat(stderr()).contains("pass the files to check explicitly");
    }

    private void git(String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", projectRoot.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).as("git " + String.join(" ", args)).isZero();
    }
}
