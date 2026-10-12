package com.devmanchego.javaguard.dist;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Plan Phase 20: the scripted end-to-end scenarios, through the packaged {@code javaguard.exe} and its bundled runtime, on real
 * fixture projects built by the real Maven - an agent session reduced to the hook payloads Claude Code sends. Each scenario
 * asserts what the agent would see (exit code, stdout, stderr) and what the user would find on disk.
 */
@EnabledOnOs(OS.WINDOWS)
class ScenariosIT {

    private static final Path STAGE = Path.of(System.getProperty("dist.stage", "target/dist/windows-x64")).toAbsolutePath();
    private static final Path FIXTURES = Path.of(System.getProperty("fixtures.dir", "../javaguard-core/src/test/resources/fixtures"))
            .toAbsolutePath().normalize();
    private static final Path EXE = STAGE.resolve("javaguard.exe");
    private static final String APP = "src/main/java/com/acme/simple/App.java";
    private static final String APP_TEST = "src/test/java/com/acme/simple/AppTest.java";
    private static final String SQL_DAO = """
            package com.acme.simple;

            import java.sql.Connection;
            import java.sql.PreparedStatement;
            import java.sql.SQLException;

            public class Dao {
                PreparedStatement find(Connection conn, String id) throws SQLException {
                    return conn.prepareStatement("SELECT * FROM t WHERE id = " + id);
                }
            }
            """;

    @TempDir
    Path work;

    @BeforeAll
    static void packaged() {
        assumeTrue(Files.isRegularFile(EXE), "Run 'mvn -Pdist package' first: " + EXE + " not found");
    }

    record Outcome(int exit, String stdout, String stderr) {
        String all() {
            return stdout + stderr;
        }
    }

    /** One agent session on one project, speaking Claude Code's hook protocol to the packaged executable. */
    private final class Session {
        final Path project;
        final String id;

        Session(String fixture, String id) throws IOException {
            this.project = copy(FIXTURES.resolve(fixture), work.resolve(id));
            this.id = id;
        }

        String common(String event) {
            return "{\"hook_event_name\":\"" + event + "\",\"session_id\":\"" + id + "\",\"cwd\":\"" + json(project) + "\"";
        }

        /** PreToolUse, the agent's write, PostToolUse: what one Write tool call produces. */
        Outcome write(String relative, String content) throws Exception {
            Path file = project.resolve(relative);
            String input = ",\"tool_name\":\"Write\",\"tool_use_id\":\"t\",\"tool_input\":{\"file_path\":\"" + json(file) + "\",\"content\":\"x\"}";
            hook("pre-edit", common("PreToolUse") + input + "}");
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return hook("post-edit", common("PostToolUse") + input + ",\"tool_response\":{\"type\":\"update\"}}");
        }

        Outcome stop() throws Exception {
            return hook("end-of-turn", common("Stop") + ",\"stop_hook_active\":false}");
        }

        Outcome hook(String gate, String payload) throws Exception {
            return run(project, payload, "hook", "--agent", "claude", "--gate", gate);
        }

        String read(String relative) throws IOException {
            return Files.readString(project.resolve(relative), StandardCharsets.UTF_8);
        }
    }

    private static Outcome run(Path cwd, String stdin, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(EXE.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).start();
        process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        byte[] out = process.getInputStream().readAllBytes();
        byte[] err = process.getErrorStream().readAllBytes();
        assertThat(process.waitFor(600, TimeUnit.SECONDS)).isTrue();
        return new Outcome(process.exitValue(), new String(out, StandardCharsets.UTF_8), new String(err, StandardCharsets.UTF_8));
    }

    private static Path copy(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path file : (Iterable<Path>) walk::iterator) {
                if (file.toString().replace('\\', '/').contains("/target/")) {
                    continue;
                }
                Path to = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(file, to);
                }
            }
        }
        return target;
    }

    private static String json(Path path) {
        return path.toString().replace('\\', '/');
    }

    // --- 1. clean edit ------------------------------------------------------------------------------

    @Test
    void aCleanEditPassesSilentlyAtBothGates() throws Exception {
        Session session = new Session("maven-simple", "clean");

        Outcome post = session.write(APP, session.read(APP).replace("\"Hello, \"", "\"Hello, \" "));
        Outcome stop = session.stop();

        assertThat(post.exit()).as(post.all()).isZero();
        assertThat(post.stderr()).isEmpty();
        assertThat(stop.exit()).as(stop.all()).isZero();
        assertThat(stop.all()).doesNotContain("BLOCK");
    }

    // --- 2. seeded blocker ---------------------------------------------------------------------------

    @Test
    void aSeededSqlInjectionIsBlockedRightAfterTheEdit() throws Exception {
        Session session = new Session("maven-simple", "blocker");

        Outcome post = session.write("src/main/java/com/acme/simple/Dao.java", SQL_DAO);

        assertThat(post.exit()).isEqualTo(2);
        assertThat(post.stderr()).startsWith("JAVAGUARD BLOCK - 1 blocking").contains("[JG-SEC-001 SQL built by concatenation]")
                .contains("src/main/java/com/acme/simple/Dao.java:9").contains("Fix: ");
    }

    // --- 3. compile break across modules -------------------------------------------------------------

    @Test
    void renamingAMethodBreaksTheDependentModuleAndTheStopIsBlockedThere() throws Exception {
        Session session = new Session("maven-multimodule", "modules");
        String foo = "module-a/src/main/java/com/acme/modulea/Foo.java";

        Outcome post = session.write(foo, session.read(foo).replace("public int value()", "public int amount()"));
        Outcome stop = session.stop();

        assertThat(post.exit()).as("the fast gate cannot see module-b").isZero();
        assertThat(stop.exit()).as(stop.all()).isEqualTo(2);
        assertThat(stop.stderr()).contains("[JAVAC Compilation error] module-b/src/main/java/com/acme/moduleb/Bar.java:7")
                .contains("cannot find symbol");
    }

    // --- 4. failing test -----------------------------------------------------------------------------

    @Test
    void aChangeThatBreaksATestIsBlockedWithExpectedAndActual() throws Exception {
        Session session = new Session("maven-simple", "failing");

        session.write(APP, session.read(APP).replace("\"Hello, \"", "\"Hi, \""));
        Outcome stop = session.stop();

        assertThat(stop.exit()).as(stop.all()).isEqualTo(2);
        assertThat(stop.stderr()).contains("[TEST Test failure] src/test/java/com/acme/simple/AppTest.java")
                .contains("expected: <Hello, World!> but was: <Hi, World!>");
    }

    // --- 5. test weakening ---------------------------------------------------------------------------

    @Test
    void deletingTheAssertionToMakeATestPassIsCaught() throws Exception {
        Session session = new Session("maven-simple", "weakening");

        Outcome post = session.write(APP_TEST, session.read(APP_TEST)
                .replace("        assertEquals(\"Hello, World!\", new App().greet(\"World\"));\n", "        new App().greet(\"World\");\n"));

        assertThat(post.exit()).as(post.all()).isEqualTo(2);
        assertThat(post.stderr()).contains("JG-TST-").contains("AppTest.java");
    }

    // --- 6. stuck loop -------------------------------------------------------------------------------

    @Test
    void anAgentThatKeepsWritingTheSameBlockedCodeIsHaltedWithAHandoff() throws Exception {
        Session session = new Session("maven-simple", "loop");
        List<Outcome> outcomes = new ArrayList<>();
        for (int attempt = 1; attempt <= 6; attempt++) {
            outcomes.add(session.write("src/main/java/com/acme/simple/Dao.java",
                    SQL_DAO.replace("public class Dao {", "public class Dao {\n    // attempt " + attempt)));
        }

        assertThat(outcomes.subList(0, 5)).allMatch(o -> o.exit() == 2);
        assertThat(outcomes.get(2).stderr()).contains("## Change of approach required");
        Outcome halt = outcomes.get(5);
        assertThat(halt.exit()).isZero();
        assertThat(halt.stdout()).startsWith("{\"continue\":false,\"stopReason\":\"JavaGuard halted the agent")
                .contains("Handoff: .javaguard/handoff/HANDOFF-");
        String handoff = session.read("AI_HANDOFF_CONTEXT.md");
        assertThat(handoff).startsWith("# JavaGuard handoff - agent stuck on JG-SEC-001").contains("## 5. Attempts so far")
                .contains("## 8. Request").doesNotContain(work.toString());
    }

    // --- 7. consultation round-trip ------------------------------------------------------------------

    @Test
    void aSecondOpinionIsRequestedFromAFakeConsultantAndDeliveredToTheAgentOnce() throws Exception {
        Session session = new Session("maven-simple", "consult");
        Path cmd = Path.of(System.getenv().getOrDefault("ComSpec", "C:\\Windows\\System32\\cmd.exe"));
        // The fake consultant ignores the handoff on stdin and answers with a fixed proposal.
        Files.writeString(session.project.resolve("javaguard.yml"), "consult:\n  enabled: true\n  order: [gemini]\n  commands:\n    gemini: '\""
                + cmd + "\" /c echo PROPOSAL-FROM-THE-FAKE-CONSULTANT'\n", StandardCharsets.UTF_8);

        Outcome consulted = null;
        for (int attempt = 1; attempt <= 6 && consulted == null; attempt++) {
            Outcome outcome = session.write("src/main/java/com/acme/simple/Dao.java",
                    SQL_DAO.replace("public class Dao {", "public class Dao {\n    // attempt " + attempt));
            if (outcome.stderr().contains("A second opinion has been requested")) {
                consulted = outcome;
            }
        }
        assertThat(consulted).as("level 2 reached").isNotNull();

        Path solution = session.project.resolve(".javaguard/handoff/SOLUTION-consult.md");
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!Files.exists(solution) && System.nanoTime() < deadline) {
            Thread.sleep(250);
        }
        assertThat(solution).as("the detached consultation wrote its answer").exists();
        Thread.sleep(1100); // the solution must be newer than the handoff on coarse file-system clocks

        Outcome delivered = session.write("src/main/java/com/acme/simple/Dao.java", SQL_DAO.replace("public class Dao {", "public class Dao {\n    // next"));
        Outcome after = session.write("src/main/java/com/acme/simple/Dao.java", SQL_DAO.replace("public class Dao {", "public class Dao {\n    // later"));

        assertThat(delivered.stderr()).contains("## Second opinion").contains("PROPOSAL-FROM-THE-FAKE-CONSULTANT");
        assertThat(after.stderr()).doesNotContain("PROPOSAL-FROM-THE-FAKE-CONSULTANT");
        assertThat(after.stdout()).doesNotContain("\"continue\":false"); // the solution reset the ladder: no HALT yet
    }

    // --- 8. applied migration ------------------------------------------------------------------------

    @Test
    void anAgentFixingAnAppliedMigrationIsBlockedAndANewMigrationPasses() throws Exception {
        Session session = new Session("maven-simple", "migration");
        Path applied = session.project.resolve("src/main/resources/db/migration/V1__customer.sql");
        Files.createDirectories(applied.getParent());
        Files.writeString(applied, "create table customer (id bigint primary key, name varchar(50));\n", StandardCharsets.UTF_8);

        Outcome edit = session.write("src/main/resources/db/migration/V1__customer.sql",
                "create table customer (id bigint primary key, name varchar(200));\n");
        Outcome fresh = session.write("src/main/resources/db/migration/V2__customer_name.sql",
                "alter table customer alter column name varchar(200);\n");

        assertThat(edit.exit()).as(edit.all()).isEqualTo(2);
        assertThat(edit.stderr()).contains("[JG-DB-001 Applied migration modified]").contains("V1__customer.sql");
        assertThat(fresh.stderr()).doesNotContain("JG-DB-");
    }

    // --- 9. derived query on a misspelled property ----------------------------------------------------

    @Test
    void aDerivedQueryOnAMisspelledPropertyIsBlockedRightAfterTheEdit() throws Exception {
        Session session = new Session("maven-spring-jpa-lombok", "derived");

        Outcome post = session.write("src/main/java/com/acme/entity/UserRepository.java", """
                package com.acme.entity;

                import java.util.List;
                import org.springframework.data.jpa.repository.JpaRepository;

                public interface UserRepository extends JpaRepository<User, Long> {
                    List<User> findByUsername(String username);
                }
                """);

        assertThat(post.exit()).as(post.all()).isEqualTo(2);
        assertThat(post.stderr()).contains("[JG-SPR-015 Derived query on an unknown property]").contains("'username'");
    }

    // --- 10. remote call without timeout inside a transaction ------------------------------------------

    @Test
    void anHttpCallWithoutTimeoutInsideATransactionIsReported() throws Exception {
        Session session = new Session("maven-spring-jpa-lombok", "remote");

        Outcome post = session.write("src/main/java/com/acme/entity/UserService.java", """
                package com.acme.entity;

                import org.springframework.transaction.annotation.Transactional;
                import org.springframework.web.client.RestTemplate;

                public class UserService {
                    private final RestTemplate rest = new RestTemplate();

                    @Transactional
                    public String sync(long id) {
                        return rest.getForObject("https://crm.example.com/users/" + id, String.class);
                    }
                }
                """);

        assertThat(post.all()).contains("JG-RES-001").contains("JG-RES-003");
    }
}
