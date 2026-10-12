package com.devmanchego.javaguard.dist.training;

import com.devmanchego.javaguard.cli.Main;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The AppCDS training workload (specification &sect;21 step 3). Run once, inside the jlink runtime, with
 * {@code -XX:ArchiveClassesAtExit}: it exercises, in one JVM, what a hook invocation loads - the CLI, configuration,
 * the adapters of all three agents, the fast pipeline (parser, AST rules, PMD, test integrity), the change tracker, the
 * end-of-turn gate when nothing changed, {@code doctor}, {@code rules} and {@code check} - against a copy of a small
 * fixture project. Nothing here is a test: failures are printed and ignored, because the archive is only a startup
 * optimization.
 */
public final class TrainingRun {

    private static final String SQL_CONCAT = """
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

    private TrainingRun() {
    }

    /** @param args {@code <project directory>} (a writable copy of a Maven project with {@code src/main/java/com/acme/simple/App.java}) */
    public static void main(String[] args) throws IOException {
        Path project = Path.of(args[0]).toAbsolutePath();
        System.setProperty("javaguard.notifications", "off");
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            Path app = project.resolve("src/main/java/com/acme/simple/App.java");
            Path dao = project.resolve("src/main/java/com/acme/simple/Dao.java");
            Files.writeString(dao, SQL_CONCAT);
            String cwd = json(project);

            run(originalIn, "--version");
            run(originalIn, "--project-dir", project.toString(), "doctor");
            run(originalIn, "--project-dir", project.toString(), "rules");
            run(originalIn, "--project-dir", project.toString(), "check", app.toString(), dao.toString());

            for (String agent : new String[] {"claude", "gemini", "codex"}) {
                String session = "training-" + agent;
                String common = "\"session_id\":\"" + session + "\",\"cwd\":\"" + cwd + "\"";
                String post = switch (agent) {
                    case "claude" -> "{\"hook_event_name\":\"PostToolUse\"," + common + ",\"tool_name\":\"Write\",\"tool_use_id\":\"t\","
                            + "\"tool_input\":{\"file_path\":\"" + json(dao) + "\",\"content\":\"x\"},\"tool_response\":{\"type\":\"create\"}}";
                    case "gemini" -> "{\"hook_event_name\":\"AfterTool\"," + common + ",\"tool_name\":\"write_file\","
                            + "\"tool_input\":{\"file_path\":\"" + json(dao) + "\",\"content\":\"x\"}}";
                    default -> "{\"hook_event_name\":\"PostToolUse\"," + common + ",\"model\":\"gpt-5\",\"turn_id\":\"t1\",\"tool_name\":\"apply_patch\","
                            + "\"tool_input\":{\"command\":\"*** Begin Patch\\n*** Update File: src/main/java/com/acme/simple/Dao.java\\n+x\\n*** End Patch\"}}";
                };
                String pre = switch (agent) {
                    case "claude" -> "{\"hook_event_name\":\"PreToolUse\"," + common + ",\"tool_name\":\"Write\",\"tool_use_id\":\"t\","
                            + "\"tool_input\":{\"file_path\":\"" + json(dao) + "\",\"content\":\"x\"}}";
                    case "gemini" -> "{\"hook_event_name\":\"BeforeTool\"," + common + ",\"tool_name\":\"write_file\","
                            + "\"tool_input\":{\"file_path\":\"" + json(dao) + "\",\"content\":\"x\"}}";
                    default -> "{\"hook_event_name\":\"PreToolUse\"," + common + ",\"model\":\"gpt-5\",\"turn_id\":\"t1\",\"tool_name\":\"apply_patch\","
                            + "\"tool_input\":{\"command\":\"*** Begin Patch\\n*** Update File: src/main/java/com/acme/simple/Dao.java\\n+x\\n*** End Patch\"}}";
                };
                String stop = switch (agent) {
                    case "claude" -> "{\"hook_event_name\":\"Stop\"," + common + ",\"stop_hook_active\":false}";
                    case "gemini" -> "{\"hook_event_name\":\"AfterAgent\"," + common + ",\"prompt_response\":\"done\",\"stop_hook_active\":false}";
                    default -> "{\"hook_event_name\":\"Stop\"," + common + ",\"model\":\"gpt-5\",\"turn_id\":\"t1\",\"stop_hook_active\":false}";
                };
                for (String[] step : new String[][] {{pre, "pre-edit"}, {post, "post-edit"}, {post, "post-edit"}, {stop, "end-of-turn"}}) {
                    run(new ByteArrayInputStream(step[0].getBytes(StandardCharsets.UTF_8)), "--project-dir", project.toString(), "hook",
                            "--agent", agent, "--gate", step[1]);
                }
            }
            run(originalIn, "--project-dir", project.toString(), "handoff");
            trainSpotBugs(project);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        System.out.println("training run done");
    }

    /** SpotBugs' start-up (detector factories, the FindSecBugs plugin) is a second of every end of turn; archive it too. */
    private static void trainSpotBugs(Path project) {
        try {
            Path classes = Files.createDirectories(project.resolve("target/training-classes"));
            Path source = project.resolve("src/main/java/com/acme/simple/Dao.java");
            javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(), source.toString());
            try (var files = Files.walk(classes)) {
                new com.devmanchego.javaguard.analysis.spotbugs.SpotBugsRunner()
                        .run(files.filter(p -> p.toString().endsWith(".class")).toList(), java.util.List.of());
            }
        } catch (Exception | LinkageError e) {
            // Training only.
        }
    }

    private static void run(InputStream stdin, String... args) {
        System.setIn(stdin);
        try {
            new CommandLine(new Main()).execute(args);
        } catch (RuntimeException e) {
            // Training only: whatever failed, the classes it loaded on the way are still archived.
        }
    }

    private static String json(Path path) {
        return path.toString().replace('\\', '/');
    }
}
