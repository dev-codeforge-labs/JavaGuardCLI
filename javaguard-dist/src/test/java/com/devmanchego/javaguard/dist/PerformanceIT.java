package com.devmanchego.javaguard.dist;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Plan Phase 20: the performance targets of specification section 20, measured through the packaged {@code javaguard.exe}
 * (wall clock from process start to exit, as an agent sees it; warm OS cache) and asserted. Two targets carry the deviation
 * the specification accepts (section 20, "Measured on the packaged build"): {@code --version} and PRE_EDIT include the
 * Launch4j wrapper. Results go to {@code target/dist/performance.md}, copied into {@code .private/spec/notes/performance.md}.
 */
@EnabledOnOs(OS.WINDOWS)
class PerformanceIT {

    private static final Path STAGE = Path.of(System.getProperty("dist.stage", "target/dist/windows-x64")).toAbsolutePath();
    private static final Path TARGET = Path.of(System.getProperty("dist.target", "target")).toAbsolutePath();
    private static final Path FIXTURES = Path.of(System.getProperty("fixtures.dir", "../javaguard-core/src/test/resources/fixtures"))
            .toAbsolutePath().normalize();
    private static final Path EXE = STAGE.resolve("javaguard.exe");
    private static final String APP = "src/main/java/com/acme/simple/App.java";

    /** Scenario -> {target, limit asserted, measured}. */
    private static final Map<String, long[]> RESULTS = new LinkedHashMap<>();

    @TempDir
    Path work;

    @BeforeAll
    static void packaged() throws Exception {
        assumeTrue(Files.isRegularFile(EXE), "Run 'mvn -Pdist package' first: " + EXE + " not found");
        // The numbers are those of an installed distribution: `javaguard install` builds the start-up archive in its folder.
        Process optimize = new ProcessBuilder(EXE.toString(), "optimize", "--force").redirectErrorStream(true).start();
        optimize.getOutputStream().close();
        String output = new String(optimize.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(optimize.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(optimize.exitValue()).as(output).isZero();
    }

    @AfterAll
    static void writeReport() throws IOException {
        if (RESULTS.isEmpty()) {
            return;
        }
        StringBuilder md = new StringBuilder("| Scenario | Target ms (spec 20) | Asserted limit ms | Measured ms |\n|---|---|---|---|\n");
        RESULTS.forEach((name, v) -> md.append("| ").append(name).append(" | ").append(v[0]).append(" | ").append(v[1]).append(" | ")
                .append(v[2]).append(" |\n"));
        Files.createDirectories(TARGET.resolve("dist"));
        Files.writeString(TARGET.resolve("dist/performance.md"), md.toString(), StandardCharsets.UTF_8);
    }

    private static void record(String scenario, long target, long limit, long measured) {
        RESULTS.put(scenario, new long[] {target, limit, measured});
        assertThat(measured).as(scenario + " (target " + target + " ms)").isLessThanOrEqualTo(limit);
    }

    // --- helpers -------------------------------------------------------------------------------------

    private Path project(String name) throws IOException {
        Path source = FIXTURES.resolve("maven-simple");
        Path target = work.resolve(name);
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path file : (Iterable<Path>) walk::iterator) {
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

    private static long run(Path cwd, String stdin, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(EXE.toString()));
        command.addAll(List.of(args));
        long start = System.nanoTime();
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor(600, TimeUnit.SECONDS)).isTrue();
        return (System.nanoTime() - start) / 1_000_000;
    }

    private interface Timed {
        long go() throws Exception;
    }

    private static long percentile(int runs, double p, Timed timed) throws Exception {
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            times.add(timed.go());
        }
        Collections.sort(times);
        return times.get(Math.min(times.size() - 1, (int) Math.ceil(p * times.size()) - 1));
    }

    private static String json(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String post(Path project, Path file, String session) {
        return "{\"hook_event_name\":\"PostToolUse\",\"session_id\":\"" + session + "\",\"cwd\":\"" + json(project) + "\",\"tool_name\":\"Write\","
                + "\"tool_use_id\":\"t\",\"tool_input\":{\"file_path\":\"" + json(file) + "\",\"content\":\"x\"},\"tool_response\":{\"type\":\"update\"}}";
    }

    private static String pre(Path project, Path file, String session) {
        return "{\"hook_event_name\":\"PreToolUse\",\"session_id\":\"" + session + "\",\"cwd\":\"" + json(project) + "\",\"tool_name\":\"Write\","
                + "\"tool_use_id\":\"t\",\"tool_input\":{\"file_path\":\"" + json(file) + "\",\"content\":\"x\"}}";
    }

    private static String stop(Path project, String session) {
        return "{\"hook_event_name\":\"Stop\",\"session_id\":\"" + session + "\",\"cwd\":\"" + json(project) + "\",\"stop_hook_active\":false}";
    }

    /** A clean class of about 300 lines that no rule objects to. */
    private static String threeHundredLines() {
        StringBuilder java = new StringBuilder("package com.acme.simple;\n\n/** Arithmetic helpers. */\npublic class Big {\n");
        for (int i = 0; i < 29; i++) {
            java.append("\n    /** Adds ").append(i).append(". */\n    public int add").append(i).append("(int value) {\n")
                    .append("        int result = value;\n        result = result + ").append(i).append(";\n")
                    .append("        if (result < 0) {\n            return 0;\n        }\n        return result;\n    }\n");
        }
        return java.append("}\n").toString();
    }

    // --- the targets ---------------------------------------------------------------------------------

    @Test
    void startup() throws Exception {
        Path project = project("startup");
        long version = percentile(7, 0.5, () -> run(project, "", "--version"));
        record("JVM start to main (`--version`), median", 250, 300, version);
    }

    @Test
    void preEdit() throws Exception {
        Path project = project("pre");
        Path app = project.resolve(APP);
        long median = percentile(9, 0.5, () -> run(project, pre(project, app, "pre"), "hook", "--agent", "claude", "--gate", "pre-edit"));
        record("PRE_EDIT, median", 300, 350, median);
    }

    @Test
    void postEditCold() throws Exception {
        Path project = project("cold");
        Path big = project.resolve("src/main/java/com/acme/simple/Big.java");
        Files.writeString(big, threeHundredLines());
        long median = percentile(5, 0.5, () -> run(project, post(project, big, "cold"), "hook", "--agent", "claude", "--gate", "post-edit"));
        record("POST_EDIT, 300-line file, no classpath cache (compile skipped), median", 800, 800, median);
    }

    @Test
    void postEditWarm() throws Exception {
        Path project = project("warm");
        assertThat(run(project, "", "--project-dir", project.toString(), "warmup")).isPositive();
        Path big = project.resolve("src/main/java/com/acme/simple/Big.java");
        Files.writeString(big, threeHundredLines());
        run(project, post(project, big, "warm"), "hook", "--agent", "claude", "--gate", "post-edit");
        long p95 = percentile(20, 0.95, () -> run(project, post(project, big, "warm"), "hook", "--agent", "claude", "--gate", "post-edit"));
        record("POST_EDIT, 300-line file, classpath cache warm, no findings, p95", 1500, 1500, p95);
    }

    @Test
    void endOfTurnWithoutJavaChanges() throws Exception {
        Path project = project("stop");
        long median = percentile(7, 0.5, () -> run(project, stop(project, "stop"), "hook", "--agent", "claude", "--gate", "end-of-turn"));
        record("END_OF_TURN, no Java changes, median", 500, 550, median);
    }

    @Test
    void endOfTurnOverheadOverTheBuildTool() throws Exception {
        Path project = project("overhead");
        List<String> tests = new ArrayList<>(List.of("com.acme.simple.AppTest"));
        for (int i = 1; i <= 4; i++) {
            Files.writeString(project.resolve("src/test/java/com/acme/simple/Greet" + i + "Test.java"), "package com.acme.simple;\n\n"
                    + "import org.junit.jupiter.api.Test;\n\nimport static org.junit.jupiter.api.Assertions.assertEquals;\n\n"
                    + "class Greet" + i + "Test {\n    @Test\n    void greets() {\n        assertEquals(\"Hello, N" + i + "!\", new App().greet(\"N" + i
                    + "\"));\n    }\n}\n");
            tests.add("com.acme.simple.Greet" + i + "Test");
        }
        Path app = project.resolve(APP);
        // Prime Maven (downloads, compilation) so both measurements below are incremental.
        List<String> mvn = mavenTest(tests);
        new ProcessBuilder(mvn).directory(project.toFile()).redirectErrorStream(true).start().waitFor(600, TimeUnit.SECONDS);

        List<Long> overheads = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            Files.writeString(app, Files.readString(app).replace("public class App {", "public class App {\n    // round " + round));
            run(project, pre(project, app, "ovh" + round), "hook", "--agent", "claude", "--gate", "pre-edit");
            run(project, post(project, app, "ovh" + round), "hook", "--agent", "claude", "--gate", "post-edit");
            long javaguard = run(project, stop(project, "ovh" + round), "hook", "--agent", "claude", "--gate", "end-of-turn");
            Files.writeString(app, Files.readString(app).replace("public class App {", "public class App {\n    // again " + round));
            long start = System.nanoTime();
            Process maven = new ProcessBuilder(mvn).directory(project.toFile()).redirectErrorStream(true).start();
            maven.getInputStream().readAllBytes();
            maven.waitFor(600, TimeUnit.SECONDS);
            long buildOnly = (System.nanoTime() - start) / 1_000_000;
            overheads.add(javaguard - buildOnly);
        }
        Collections.sort(overheads);
        record("END_OF_TURN overhead over `mvn test` alone, 1 module, " + tests.size() + " affected test classes, median", 1000, 1500,
                overheads.get(1));
    }

    private static List<String> mavenTest(List<String> tests) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> command = new ArrayList<>(windows ? List.of("cmd", "/c", "mvn") : List.of("mvn"));
        command.addAll(List.of("-q", "-B", "-o", "test", "-Dtest=" + String.join(",", tests), "-Dsurefire.failIfNoSpecifiedTests=false",
                "-DfailIfNoTests=false"));
        return command;
    }
}
