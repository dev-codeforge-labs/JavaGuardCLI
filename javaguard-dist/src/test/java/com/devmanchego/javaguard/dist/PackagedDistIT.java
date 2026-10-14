package com.devmanchego.javaguard.dist;

import com.devmanchego.javaguard.cli.Main;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Plan Phase 19, against the real package that {@code mvn -Pdist package} built (run with {@code mvn -Pdist verify}):
 * the executable starts, finds its runtime, archive and plugin; every agent's payloads give byte-for-byte the output and
 * exit code that the same payloads give in the JVM of the tests; the in-process compiler, Error Prone and FindSecBugs
 * work inside the jlink runtime; both zips have the contents of specification section 21. Also writes
 * {@code target/dist/size-report.md} with sizes and measured latencies.
 */
@EnabledOnOs(OS.WINDOWS)
class PackagedDistIT {

    private static final Path STAGE = Path.of(System.getProperty("dist.stage", "target/dist/windows-x64")).toAbsolutePath();
    private static final Path PORTABLE = Path.of(System.getProperty("dist.portable", "target/dist/portable")).toAbsolutePath();
    private static final Path TARGET = Path.of(System.getProperty("dist.target", "target")).toAbsolutePath();
    private static final String VERSION = System.getProperty("dist.version", "1.0.0");
    private static final Path FIXTURE = Path.of(System.getProperty("fixtures.dir", "../javaguard-core/src/test/resources/fixtures"))
            .resolve("maven-simple").toAbsolutePath().normalize();
    private static final Path EXE = STAGE.resolve("javaguard.exe");
    private static final String APP = "src/main/java/com/acme/simple/App.java";
    private static final String SQL_CONCAT = """
            package com.acme.simple;

            import java.sql.Connection;
            import java.sql.PreparedStatement;
            import java.sql.SQLException;

            public class App {
                PreparedStatement find(Connection conn, String id) throws SQLException {
                    return conn.prepareStatement("SELECT * FROM t WHERE id = " + id);
                }
            }
            """;

    @TempDir
    Path work;

    @BeforeAll
    static void packaged() {
        System.setProperty("javaguard.notifications", "off");
        assumeTrue(Files.isRegularFile(EXE), "Run 'mvn -Pdist package' first: " + EXE + " not found");
    }

    /** What a process (or an in-JVM run) printed. */
    record Outcome(int exit, String stdout, String stderr) {
    }

    private Path project(String name) throws IOException {
        Path project = work.resolve(name);
        try (Stream<Path> walk = Files.walk(FIXTURE)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path target = project.resolve(FIXTURE.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
        // The replay compares the executable with the JVM byte for byte: a machine slow enough to exhaust the 3 s fast-gate
        // budget in only one of the two runs (a shared CI runner) would make them differ for reasons that are not the code's.
        Files.writeString(project.resolve("javaguard.yml"), "fastGate:\n  budgetMillis: 60000\n");
        return project;
    }

    private static Outcome viaExe(String stdin, Path cwd, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(EXE.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).start();
        process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        byte[] out = process.getInputStream().readAllBytes();
        byte[] err = process.getErrorStream().readAllBytes();
        assertThat(process.waitFor(300, TimeUnit.SECONDS)).as("the executable exits").isTrue();
        return new Outcome(process.exitValue(), new String(out, StandardCharsets.UTF_8), new String(err, StandardCharsets.UTF_8));
    }

    /** The same arguments in this JVM, which has the same classes but the plain classpath. */
    private static Outcome inJvm(String stdin, String... args) {
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exit = new CommandLine(new Main()).execute(args);
            return new Outcome(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static String json(Path path) {
        return path.toString().replace('\\', '/');
    }

    // --- layout and smoke ----------------------------------------------------------------------

    @Test
    void thePackageHasEverythingSection21Lists() throws IOException {
        for (String name : List.of("javaguard.exe", "app.jsa", "javaguard-" + VERSION + "-all.jar", "plugins/findsecbugs-plugin.jar",
                "runtime/bin/java.exe", "README.md", "USAGE.md", "LICENSE", "NOTICE", "THIRD-PARTY.txt")) {
            assertThat(STAGE.resolve(name)).as(name).isRegularFile();
        }
        Path windows = TARGET.resolve("javaguard-" + VERSION + "-windows-x64.zip");
        Path portable = TARGET.resolve("javaguard-" + VERSION + "-portable.zip");
        assertThat(entries(windows)).contains("javaguard-" + VERSION + "/javaguard.exe", "javaguard-" + VERSION + "/app.jsa",
                "javaguard-" + VERSION + "/runtime/bin/java.exe", "javaguard-" + VERSION + "/plugins/findsecbugs-plugin.jar",
                "javaguard-" + VERSION + "/LICENSE", "javaguard-" + VERSION + "/README.md", "javaguard-" + VERSION + "/USAGE.md");
        assertThat(entries(portable)).contains("javaguard-" + VERSION + "/javaguard-" + VERSION + "-all.jar",
                "javaguard-" + VERSION + "/javaguard.cmd", "javaguard-" + VERSION + "/javaguard",
                "javaguard-" + VERSION + "/plugins/findsecbugs-plugin.jar", "javaguard-" + VERSION + "/USAGE.md");
        assertThat(entries(portable)).noneMatch(e -> e.contains("/runtime/") || e.endsWith("javaguard.exe"));
    }

    private static List<String> entries(Path zipFile) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            return Collections.list(zip.entries()).stream().map(ZipEntry::getName).toList();
        }
    }

    @Test
    void versionAndDoctorRunFromTheExecutable() throws Exception {
        Path project = project("smoke");

        Outcome version = viaExe("", project, "--version");
        Outcome doctor = viaExe("", project, "--project-dir", project.toString(), "doctor");

        assertThat(version.exit()).isZero();
        assertThat(version.stdout().strip()).isEqualTo("javaguard " + VERSION);
        assertThat(doctor.exit()).as(doctor.stdout() + doctor.stderr()).isZero();
        assertThat(doctor.stdout()).contains("[OK  ] Java runtime").contains("with the compiler module")
                .contains("[OK  ] SpotBugs").contains("findsecbugs-plugin.jar").contains("[OK  ] Project");
    }

    @Test
    void anEmptyPayloadIsFailedOpenExactlyAsInTheJvm() throws Exception {
        Outcome packaged = viaExe("", work, "hook", "--agent", "claude", "--gate", "post-edit");
        Outcome reference = inJvm("", "hook", "--agent", "claude", "--gate", "post-edit");

        assertThat(packaged.exit()).isZero();
        assertThat(packaged).isEqualTo(reference);
    }

    // --- payload replays: exe equals in-JVM --------------------------------------------------------

    private static String payloads(String agent, String gate, Path project, Path file, String session) {
        String common = "\"session_id\":\"" + session + "\",\"cwd\":\"" + json(project) + "\"";
        String edit = "\"tool_input\":{\"file_path\":\"" + json(file) + "\",\"content\":\"x\"}";
        return switch (agent + "/" + gate) {
            case "claude/pre-edit" -> "{\"hook_event_name\":\"PreToolUse\"," + common + ",\"tool_name\":\"Write\",\"tool_use_id\":\"t\"," + edit + "}";
            case "claude/post-edit" -> "{\"hook_event_name\":\"PostToolUse\"," + common + ",\"tool_name\":\"Write\",\"tool_use_id\":\"t\"," + edit
                    + ",\"tool_response\":{\"type\":\"create\"}}";
            case "claude/end-of-turn" -> "{\"hook_event_name\":\"Stop\"," + common + ",\"stop_hook_active\":false}";
            case "gemini/pre-edit" -> "{\"hook_event_name\":\"BeforeTool\"," + common + ",\"tool_name\":\"write_file\"," + edit + "}";
            case "gemini/post-edit" -> "{\"hook_event_name\":\"AfterTool\"," + common + ",\"tool_name\":\"write_file\"," + edit + "}";
            case "gemini/end-of-turn" -> "{\"hook_event_name\":\"AfterAgent\"," + common + ",\"prompt_response\":\"done\",\"stop_hook_active\":false}";
            case "codex/pre-edit", "codex/post-edit" -> "{\"hook_event_name\":\"" + (gate.equals("pre-edit") ? "PreToolUse" : "PostToolUse") + "\"," + common
                    + ",\"model\":\"gpt-5\",\"turn_id\":\"t1\",\"tool_name\":\"apply_patch\",\"tool_input\":{\"command\":\"*** Begin Patch\\n*** Add File: "
                    + project.relativize(file).toString().replace('\\', '/') + "\\n+x\\n*** End Patch\"}}";
            default -> "{\"hook_event_name\":\"Stop\"," + common + ",\"model\":\"gpt-5\",\"turn_id\":\"t1\",\"stop_hook_active\":false}";
        };
    }

    private static final String DAO = "src/main/java/com/acme/simple/Dao.java";
    private static final String CLEAN_DAO = "package com.acme.simple;\n\npublic class Dao {\n    public int one() {\n        return 1;\n    }\n}\n";
    private static final String SQL_DAO = SQL_CONCAT.replace("public class App", "public class Dao");
    private static final String NPE_DAO = "package com.acme.simple;\n\npublic class Dao {\n    public int length() {\n"
            + "        String text = null;\n        return text.length();\n    }\n"
            + "    public int roll() {\n        return new java.util.Random().nextInt(6);\n    }\n}\n";

    private void assertSame(String label, Outcome packaged, Outcome reference) {
        assertThat(packaged.exit()).as(label + " exit").isEqualTo(reference.exit());
        assertThat(packaged.stdout()).as(label + " stdout").isEqualTo(reference.stdout());
        assertThat(packaged.stderr()).as(label + " stderr").isEqualTo(reference.stderr());
    }

    @Test
    void everyAgentsPayloadsGiveTheSameBytesAndExitCodeInTheExecutableAndInTheJvm() throws Exception {
        for (String agent : List.of("claude", "gemini", "codex")) {
            for (String content : List.of("clean", "sql")) {
                Path exeProject = project("exe-" + agent + "-" + content);
                Path jvmProject = project("jvm-" + agent + "-" + content);
                String session = "replay-" + agent + "-" + content;
                String[] hook = {"hook", "--agent", agent, "--gate", null};

                // 1. nothing changed yet: both pre-edit and end-of-turn answer, and agree
                for (String gate : List.of("pre-edit", "end-of-turn")) {
                    hook[4] = gate;
                    assertSame(agent + " " + gate + " before", viaExe(payloads(agent, gate, exeProject, exeProject.resolve(DAO), session), exeProject, hook),
                            inJvm(payloads(agent, gate, jvmProject, jvmProject.resolve(DAO), session), hook));
                }
                // 2. the agent writes a new file, then post-edit
                for (Path project : List.of(exeProject, jvmProject)) {
                    Files.writeString(project.resolve(DAO), content.equals("sql") ? SQL_DAO : CLEAN_DAO);
                }
                hook[4] = "post-edit";
                Outcome packaged = viaExe(payloads(agent, "post-edit", exeProject, exeProject.resolve(DAO), session), exeProject, hook);
                Outcome reference = inJvm(payloads(agent, "post-edit", jvmProject, jvmProject.resolve(DAO), session), hook);
                assertSame(agent + " post-edit " + content, packaged, reference);
                if (content.equals("sql")) {
                    assertThat(packaged.stdout() + packaged.stderr()).as(agent + " reports the injection").contains("JG-SEC-001");
                } else {
                    assertThat(packaged.stderr()).isEmpty();
                }
            }
        }
    }

    // --- the analysis engines inside the runtime ----------------------------------------------------

    @Test
    void theInProcessCompilerAndErrorProneRunInsideTheJlinkRuntime() throws Exception {
        Path project = project("compile");
        Outcome warmup = viaExe("", project, "--project-dir", project.toString(), "warmup");
        assertThat(warmup.exit()).as(warmup.stdout() + warmup.stderr()).isZero();
        Files.writeString(project.resolve(APP), Files.readString(FIXTURE.resolve(APP)).replace("return \"Hello, \" + name + \"!\";", "return undefinedVariable;"));

        Outcome post = viaExe(payloads("claude", "post-edit", project, project.resolve(APP), "compile"), project, "hook", "--agent", "claude",
                "--gate", "post-edit");

        assertThat(post.stdout() + post.stderr()).contains("cannot find symbol");
    }

    @Test
    void spotBugsRunsInTheExecutableAtTheEndOfTheTurn() throws Exception {
        Path project = project("spotbugs");
        assertThat(viaExe("", project, "--project-dir", project.toString(), "warmup").exit()).isZero();
        Files.writeString(project.resolve(DAO), NPE_DAO); // compiles, no JavaGuard rule objects: only the bytecode analysis can see it
        viaExe(payloads("claude", "post-edit", project, project.resolve(DAO), "sb"), project, "hook", "--agent", "claude", "--gate", "post-edit");

        Outcome stop = viaExe(payloads("claude", "end-of-turn", project, project.resolve(DAO), "sb"), project, "hook", "--agent", "claude",
                "--gate", "end-of-turn");

        assertThat(stop.stdout() + stop.stderr()).as("end-of-turn output").contains("2 advisory");
        // An end-of-turn advisory goes to the user as a summary; the finding itself is in the full report.
        assertThat(Files.readString(project.resolve(".javaguard/reports/last-report.md"))).contains("SB:NP_");
        // java.util.Random is a FindSecBugs pattern (PREDICTABLE_RANDOM), reported under JG-SEC-006: proves the plugin loaded from plugins/.
        assertThat(Files.readString(project.resolve(".javaguard/reports/last-report.md"))).contains("JG-SEC-006");
    }

    // --- sizes and latencies -------------------------------------------------------------------------

    @Test
    void writesTheSizeAndLatencyReport() throws Exception {
        Path project = project("latency");
        Files.writeString(project.resolve(APP), Files.readString(FIXTURE.resolve(APP)));
        String pre = payloads("claude", "pre-edit", project, project.resolve(APP), "lat");
        String post = payloads("claude", "post-edit", project, project.resolve(APP), "lat");

        long version = median(5, () -> viaExe("", project, "--version"));
        long preEdit = median(7, () -> viaExe(pre, project, "hook", "--agent", "claude", "--gate", "pre-edit"));
        viaExe(post, project, "hook", "--agent", "claude", "--gate", "post-edit");
        long postEdit = median(5, () -> viaExe(post, project, "hook", "--agent", "claude", "--gate", "post-edit"));
        long stopNoChanges = median(5, () -> viaExe(payloads("claude", "end-of-turn", project, project.resolve(APP), "lat2"), project,
                "hook", "--agent", "claude", "--gate", "end-of-turn"));

        StringBuilder report = new StringBuilder("# Distribution size and latency report\n\n## Sizes\n\n| Item | MB |\n|---|---|\n");
        for (Path file : List.of(TARGET.resolve("javaguard-" + VERSION + "-windows-x64.zip"), TARGET.resolve("javaguard-" + VERSION + "-portable.zip"),
                STAGE.resolve("javaguard-" + VERSION + "-all.jar"), STAGE.resolve("app.jsa"), STAGE.resolve("javaguard.exe"),
                STAGE.resolve("plugins/findsecbugs-plugin.jar"))) {
            report.append("| ").append(TARGET.getParent().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/'))
                    .append(" | ").append(String.format("%.1f", Files.size(file) / 1_048_576.0)).append(" |\n");
        }
        report.append("| runtime/ | ").append(String.format("%.1f", directorySize(STAGE.resolve("runtime")) / 1_048_576.0)).append(" |\n");
        report.append("\n## Latency through javaguard.exe (median of runs, warm OS cache, wall clock including process creation)\n\n")
                .append("| Scenario | Target (spec section 20) | Measured ms |\n|---|---|---|\n")
                .append("| `--version` (JVM start to main) | 250 | ").append(version).append(" |\n")
                .append("| PRE_EDIT | 300 | ").append(preEdit).append(" |\n")
                .append("| POST_EDIT, clean 300-line-class-sized file, no classpath cache | 800 (cold) | ").append(postEdit).append(" |\n")
                .append("| END_OF_TURN, no Java changes | 500 | ").append(stopNoChanges).append(" |\n");
        Files.createDirectories(TARGET.resolve("dist"));
        Files.writeString(TARGET.resolve("dist/size-report.md"), report.toString(), StandardCharsets.UTF_8);

        // Not the specification's targets (see the report and spec section 20): only a guard against the package becoming unusable.
        assertThat(version).isLessThan(2_000);
        assertThat(preEdit).isLessThan(2_000);
        assertThat(postEdit).isLessThan(10_000);
    }

    private interface Run {
        Outcome go() throws Exception;
    }

    private static long median(int runs, Run run) throws Exception {
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            run.go();
            times.add((System.nanoTime() - start) / 1_000_000);
        }
        Collections.sort(times);
        return times.get(times.size() / 2);
    }

    private static long directorySize(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        }
    }
}
