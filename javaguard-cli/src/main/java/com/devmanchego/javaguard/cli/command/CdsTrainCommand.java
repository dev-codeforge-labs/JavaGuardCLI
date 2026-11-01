package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.cli.ExitCode;
import com.devmanchego.javaguard.cli.Main;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The training workload of the start-up archive (see {@link StartupArchive}); run by {@code javaguard optimize} under
 * {@code -XX:ArchiveClassesAtExit}, never by a user. In one JVM it exercises what a hook invocation loads - the CLI,
 * configuration, the adapters of all three agents, the fast pipeline, the change tracker, the end-of-turn gate, {@code doctor},
 * {@code rules} and {@code check} - against a tiny project it creates in the directory it is given. Nothing here is a test:
 * failures are ignored, because the archive is only a start-up optimization.
 */
@Command(name = "cds-train", hidden = true, description = "Internal: training workload for the start-up archive.")
public final class CdsTrainCommand implements Callable<Integer> {

    private static final String POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.acme</groupId>
              <artifactId>training</artifactId>
              <version>1.0.0</version>
              <properties>
                <maven.compiler.release>17</maven.compiler.release>
                <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
              </properties>
            </project>
            """;

    private static final String APP = """
            package com.acme.simple;

            public class App {
                public String greet(String name) {
                    return "Hello, " + name + "!";
                }
            }
            """;

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

    private static final String PATCH_FILE = "src/main/java/com/acme/simple/Dao.java";

    @Parameters(index = "0", paramLabel = "<workDir>", description = "An empty, writable directory.")
    private Path workDir;

    @Override
    public Integer call() throws IOException {
        System.setProperty("javaguard.notifications", "off");
        System.setProperty("javaguard.training", "true");
        Path project = Files.createDirectories(workDir.toAbsolutePath().resolve("project"));
        Path sources = Files.createDirectories(project.resolve("src/main/java/com/acme/simple"));
        Files.writeString(project.resolve("pom.xml"), POM, StandardCharsets.UTF_8);
        Path app = sources.resolve("App.java");
        Path dao = sources.resolve("Dao.java");
        Files.writeString(app, APP, StandardCharsets.UTF_8);
        Files.writeString(dao, SQL_CONCAT, StandardCharsets.UTF_8);

        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            String cwd = json(project);
            String daoJson = json(dao);
            run(originalIn, "--version");
            run(originalIn, "--project-dir", project.toString(), "doctor");
            run(originalIn, "--project-dir", project.toString(), "rules");
            run(originalIn, "--project-dir", project.toString(), "check", app.toString(), dao.toString());

            for (String agent : List.of("claude", "gemini", "codex")) {
                String common = "\"session_id\":\"training-" + agent + "\",\"cwd\":\"" + cwd + "\"";
                String patch = "{\"command\":\"*** Begin Patch\\n*** Update File: " + PATCH_FILE + "\\n+x\\n*** End Patch\"}";
                String post = switch (agent) {
                    case "claude" -> "{\"hook_event_name\":\"PostToolUse\"," + common + ",\"tool_name\":\"Write\",\"tool_use_id\":\"t\","
                            + "\"tool_input\":{\"file_path\":\"" + daoJson + "\",\"content\":\"x\"},\"tool_response\":{\"type\":\"create\"}}";
                    case "gemini" -> "{\"hook_event_name\":\"AfterTool\"," + common + ",\"tool_name\":\"write_file\","
                            + "\"tool_input\":{\"file_path\":\"" + daoJson + "\",\"content\":\"x\"}}";
                    default -> "{\"hook_event_name\":\"PostToolUse\"," + common + ",\"model\":\"gpt-5\",\"turn_id\":\"t1\","
                            + "\"tool_name\":\"apply_patch\",\"tool_input\":" + patch + "}";
                };
                String pre = switch (agent) {
                    case "claude" -> "{\"hook_event_name\":\"PreToolUse\"," + common + ",\"tool_name\":\"Write\",\"tool_use_id\":\"t\","
                            + "\"tool_input\":{\"file_path\":\"" + daoJson + "\",\"content\":\"x\"}}";
                    case "gemini" -> "{\"hook_event_name\":\"BeforeTool\"," + common + ",\"tool_name\":\"write_file\","
                            + "\"tool_input\":{\"file_path\":\"" + daoJson + "\",\"content\":\"x\"}}";
                    default -> "{\"hook_event_name\":\"PreToolUse\"," + common + ",\"model\":\"gpt-5\",\"turn_id\":\"t1\","
                            + "\"tool_name\":\"apply_patch\",\"tool_input\":" + patch + "}";
                };
                String stop = switch (agent) {
                    case "claude" -> "{\"hook_event_name\":\"Stop\"," + common + ",\"stop_hook_active\":false}";
                    case "gemini" -> "{\"hook_event_name\":\"AfterAgent\"," + common + ",\"prompt_response\":\"done\",\"stop_hook_active\":false}";
                    default -> "{\"hook_event_name\":\"Stop\"," + common + ",\"model\":\"gpt-5\",\"turn_id\":\"t1\",\"stop_hook_active\":false}";
                };
                for (String[] step : new String[][] {{pre, "pre-edit"}, {post, "post-edit"}, {post, "post-edit"}, {stop, "end-of-turn"}}) {
                    run(new ByteArrayInputStream(step[0].getBytes(StandardCharsets.UTF_8)), "--project-dir", project.toString(),
                            "hook", "--agent", agent, "--gate", step[1]);
                }
            }
            run(originalIn, "--project-dir", project.toString(), "handoff");
            trainSpotBugs(project, dao);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return ExitCode.SUCCESS.code();
    }

    /** SpotBugs' start-up (detector factories, the FindSecBugs plugin) is a second of every end of turn; archive it too. */
    private static void trainSpotBugs(Path project, Path source) {
        try {
            Path classes = Files.createDirectories(project.resolve("target/training-classes"));
            javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(), source.toString());
            try (var files = Files.walk(classes)) {
                new com.devmanchego.javaguard.analysis.spotbugs.SpotBugsRunner()
                        .run(files.filter(p -> p.toString().endsWith(".class")).toList(), List.of());
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
