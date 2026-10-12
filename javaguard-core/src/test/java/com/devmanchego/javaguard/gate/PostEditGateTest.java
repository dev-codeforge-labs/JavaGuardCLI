package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.build.ClasspathCache;
import com.devmanchego.javaguard.build.ClasspathCacheStore;
import com.devmanchego.javaguard.build.maven.MavenClasspathExtractor;
import com.devmanchego.javaguard.change.ChangeTracker;
import com.devmanchego.javaguard.change.GitClient;
import com.devmanchego.javaguard.config.ConfigException;
import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.model.ChangeType;
import com.devmanchego.javaguard.model.EditFragment;
import com.devmanchego.javaguard.model.FileChange;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.HookEvent;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.project.BuildTool;
import com.devmanchego.javaguard.project.ModuleInfo;
import com.devmanchego.javaguard.project.ProjectDetector;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 7: the POST_EDIT gate end to end in the JVM, on a copy of the real
 * {@code maven-simple} fixture (specification &sect;10.2). Payload fixtures for the
 * agent-facing bytes are covered by {@code HookCommandTest}.
 */
class PostEditGateTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures").toAbsolutePath().normalize();
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final String APP = "src/main/java/com/acme/simple/App.java";

    private static final String CLEAN_APP = """
            package com.acme.simple;

            public class App {
                public String greet(String name) {
                    return "Hello, " + name + "!";
                }
            }
            """;

    private static final String BROKEN_APP = CLEAN_APP.replace(
            "return \"Hello, \" + name + \"!\";", "return nope(\"Hello, \" + name + \"!\");");

    @TempDir
    Path home;

    @TempDir
    Path project;

    private JavaGuardPaths paths;
    private ChangeTracker tracker;

    @BeforeEach
    void copyFixture() throws IOException {
        copyTree(FIXTURES.resolve("maven-simple"), project);
        paths = new JavaGuardPaths(project);
        paths.ensureLayout();
        tracker = new ChangeTracker(paths, GitClient.none(), Clock.fixed(Instant.parse("2026-10-04T10:00:00Z"), ZoneOffset.UTC));
    }

    private JavaGuardConfig config(String... envPairs) throws ConfigException {
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("JAVAGUARD_FASTGATE_BUDGETMILLIS", "120000");
        for (int i = 0; i < envPairs.length; i += 2) {
            env.put(envPairs[i], envPairs[i + 1]);
        }
        return ConfigLoader.load(ConfigSources.standard(project, Optional.empty(), home, env)).config();
    }

    private GateResult run(JavaGuardConfig config, FileChange... changes) {
        HookEvent event = new HookEvent(AgentKind.CLAUDE, GateKind.POST_EDIT, "s1", project, Optional.empty(), "Edit",
                List.of(changes), false, Optional.empty(), Instant.parse("2026-10-04T10:00:00Z"));
        return new PostEditGate(paths, config, tracker, Clock.systemUTC()).run(event);
    }

    private Path write(String relative, String content) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static FileChange created(Path file) {
        return FileChange.rewrite(file, ChangeType.ADD);
    }

    private static FileChange edited(Path file, String oldText, String newText) {
        return FileChange.edit(file, List.of(new EditFragment(oldText, newText, false)));
    }

    @Test
    void cleanNewFilePasses() throws Exception {
        Path file = write(APP, CLEAN_APP);

        GateResult result = run(config(), created(file));

        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        assertThat(result.blocking()).isEmpty();
        assertThat(result.advisory()).isEmpty();
    }

    @Test
    void seededSqlConcatenationBlocks() throws Exception {
        Path file = write(APP, """
                package com.acme.simple;

                import java.sql.Connection;
                import java.sql.PreparedStatement;
                import java.sql.SQLException;

                public class App {
                    PreparedStatement run(Connection conn, String id) throws SQLException {
                        return conn.prepareStatement("SELECT * FROM t WHERE id = " + id);
                    }
                }
                """);

        GateResult result = run(config(), created(file));

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).extracting(Finding::ruleId).contains("JG-SEC-001");
        assertThat(result.blocking().get(0).file()).contains(Path.of("src/main/java/com/acme/simple/App.java"));
        assertThat(result.fullReport()).isPresent();
        assertThat(project.resolve(result.fullReport().get())).isRegularFile();
    }

    @Test
    void syntaxErrorBlocksAndStopsFurtherAnalysisOfThatFile() throws Exception {
        Path file = write(APP, "package com.acme.simple;\n\npublic class App {\n    void m( {\n}\n");

        GateResult result = run(config(), created(file));

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).extracting(Finding::ruleId).containsExactly("JG-SYN-001");
        assertThat(result.advisory()).isEmpty();
    }

    @Test
    void onlyTheProblemTheEditIntroducedIsReported() throws Exception {
        String legacy = """
                package com.acme.simple;

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
        Path file = write(APP, legacy);
        String edited = legacy.replace("return 1;", "System.out.println(\"debug\");\n        return 1;");
        Files.writeString(file, edited, StandardCharsets.UTF_8);

        GateResult result = run(config(), edited(file, "return 1;", "System.out.println(\"debug\");\n        return 1;"));

        List<Finding> all = Stream.concat(result.blocking().stream(), result.advisory().stream()).toList();
        assertThat(all).extracting(Finding::ruleId).contains("JG-AI-001")
                .doesNotContain("JG-REL-001", "JG-REL-003", "PMD:EmptyCatchBlock", "PMD:CloseResource");

        // Control: the same file with an empty baseline does carry the legacy findings, so the
        // assertion above proves scoping rather than a rule that never fires.
        GateResult unscoped = new FastGatePipeline(paths, config(), Clock.systemUTC())
                .run(GateKind.POST_EDIT, List.of(new FastGatePipeline.FileUnderCheck(file, APP, "")), List.of());
        assertThat(Stream.concat(unscoped.blocking().stream(), unscoped.advisory().stream()).map(Finding::ruleId))
                .anyMatch(id -> id.equals("JG-REL-001") || id.equals("JG-REL-003") || id.startsWith("PMD:"));
    }

    @Test
    void exhaustedBudgetGivesAPartialResultWithANoteAndNeverBlocks() throws Exception {
        Path file = write(APP, """
                package com.acme.simple;

                import java.sql.Connection;
                import java.sql.PreparedStatement;
                import java.sql.SQLException;

                public class App {
                    PreparedStatement run(Connection conn, String id) throws SQLException {
                        return conn.prepareStatement("SELECT * FROM t WHERE id = " + id);
                    }
                }
                """);

        GateResult result = new FastGatePipeline(paths, config(), Clock.systemUTC(), java.time.Duration.ofMillis(1))
                .run(GateKind.POST_EDIT, List.of(new FastGatePipeline.FileUnderCheck(file, APP, "")), List.of());

        assertThat(result.verdict()).isNotEqualTo(Verdict.BLOCK);
        assertThat(result.notes()).anyMatch(n -> n.contains("budget") && n.contains("exhausted"));
    }

    @Test
    void filesOutsideSourceRootsAndGeneratedFilesAreSkipped() throws Exception {
        String bad = "package x;\npublic class Bad { void m( { }\n";
        Path outside = write("scripts/Bad.java", bad);
        Path generated = write("target/generated-sources/annotations/Gen.java", bad);
        Path nonJava = write("src/main/resources/application.yml", "a: [");

        GateResult result = run(config(), created(outside), created(generated), created(nonJava));

        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void generatedAnnotationSkipsTheFile() throws Exception {
        Path file = write(APP, "package com.acme.simple;\n\n@javax.annotation.Generated(\"x\")\npublic class App { void m( { }\n");

        assertThat(run(config(), created(file)).verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void excludedFilesAreSkipped() throws Exception {
        Files.writeString(project.resolve("javaguard.yml"), "exclude:\n  - \"src/main/java/com/acme/simple/**\"\n");
        Path file = write(APP, "package com.acme.simple;\npublic class App { void m( { }\n");

        assertThat(run(config(), created(file)).verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void fastGateDisabledStillTracksButDoesNotAnalyze() throws Exception {
        Path file = write(APP, "package com.acme.simple;\npublic class App { void m( { }\n");

        GateResult result = run(config("JAVAGUARD_FASTGATE_ENABLED", "false"), created(file));

        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        assertThat(tracker.ledger("s1").files()).containsKey(APP);
    }

    @Test
    void compilationIsSkippedWithANoteWhenTheClasspathCacheIsMissing() throws Exception {
        Path file = write(APP, BROKEN_APP);

        GateResult result = run(config(), created(file));

        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        assertThat(result.notes()).anyMatch(n -> n.contains("classpath cache is missing") && n.contains("javaguard warmup"));
    }

    @Test
    void compileErrorIsAdvisoryWithTheEndOfTurnPrefixAndNothingIsWrittenUnderTarget() throws Exception {
        buildClasspathCache();
        Path file = write(APP, BROKEN_APP);

        GateResult result = run(config(), created(file));

        assertThat(result.verdict()).isEqualTo(Verdict.ADVISE);
        assertThat(result.blocking()).isEmpty();
        Finding javac = result.advisory().stream().filter(f -> f.ruleId().equals("JAVAC")).findFirst()
                .orElseThrow(() -> new AssertionError("Expected a JAVAC advisory, got " + result.advisory()));
        assertThat(javac.message()).startsWith(FastGatePolicy.COMPILE_PREFIX).contains("cannot find symbol");
        assertThat(project.resolve("target")).doesNotExist();
    }

    @Test
    void compileErrorsBlockWhenConfiguredTo() throws Exception {
        buildClasspathCache();
        Path file = write(APP, BROKEN_APP);

        GateResult result = run(config("JAVAGUARD_FASTGATE_COMPILEERRORS", "block"), created(file));

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).extracting(Finding::ruleId).contains("JAVAC");
    }

    @Test
    void aCleanFileCompilesWithoutFindingsOnceTheCacheExists() throws Exception {
        buildClasspathCache();
        Path file = write(APP, CLEAN_APP);

        GateResult result = run(config(), created(file));

        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void aStaleCacheSkipsCompilation() throws Exception {
        buildClasspathCache();
        Files.writeString(project.resolve("pom.xml"),
                Files.readString(project.resolve("pom.xml")).replace("</project>", "<!-- changed --></project>"));
        Path file = write(APP, CLEAN_APP);

        GateResult result = run(config(), created(file));

        assertThat(result.notes()).anyMatch(n -> n.contains("build file changed"));
    }

    @Test
    void policyTreatsInfrastructureFindingsAsNotesNeverAsBlockers() throws Exception {
        FastGatePolicy policy = new FastGatePolicy(config());
        Finding infra = new Finding("JG-INFRA", "Build tool missing", com.devmanchego.javaguard.model.Category.INFRASTRUCTURE,
                com.devmanchego.javaguard.model.Severity.BLOCKER, "maven", Optional.empty(), 0, 0, 0,
                "Maven was not found.", "Install Maven.", Optional.empty(), Map.of(),
                new com.devmanchego.javaguard.model.Fingerprint("f"));

        FastGatePolicy.Partition partition = policy.partition(List.of(infra));

        assertThat(partition.blocking()).isEmpty();
        assertThat(partition.advisory()).isEmpty();
        assertThat(partition.infrastructureNotes()).containsExactly("Maven was not found.");
    }

    private void buildClasspathCache() {
        ModuleInfo module = ProjectDetector.detect(project, Optional.of(BuildTool.MAVEN)).orElseThrow();
        Optional<ClasspathCache> cache = new MavenClasspathExtractor().extract(module, false, true, List.of(),
                System.getenv(), WINDOWS);
        assertThat(cache).as("classpath extraction against the real fixture").isPresent();
        new ClasspathCacheStore(paths.classpathCacheDir()).put(module.moduleRoot(), cache.get());
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else if (!source.toString().replace('\\', '/').contains("/target/")) {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
