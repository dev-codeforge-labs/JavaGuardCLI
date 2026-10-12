package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.cli.Main;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Map;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;

/**
 * Shared scaffolding for command tests: a temporary project root and user home, captured standard
 * streams, an environment free of the agent variables the test JVM may have inherited.
 */
abstract class CommandTestBase {

    static final String MINIMAL_POM = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
            + "<modelVersion>4.0.0</modelVersion><groupId>x</groupId><artifactId>y</artifactId>"
            + "<version>1</version><properties><maven.compiler.release>21</maven.compiler.release></properties></project>";

    @TempDir
    Path projectRoot;

    @TempDir
    Path userHome;

    private InputStream originalIn;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private String originalHome;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void captureStreams() {
        originalIn = System.in;
        originalOut = System.out;
        originalErr = System.err;
        originalHome = System.getProperty("user.home");
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        System.setProperty("user.home", userHome.toString());
        ProcessEnvironment.overrideForTests(Map.of());
    }

    @AfterEach
    void restoreStreams() {
        System.setIn(originalIn);
        System.setOut(originalOut);
        System.setErr(originalErr);
        System.setProperty("user.home", originalHome);
        ProcessEnvironment.overrideForTests(null);
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            if (handler instanceof FileHandler) {
                handler.close();
                root.removeHandler(handler);
            }
        }
    }

    /** Runs {@code javaguard --project-dir <root> <args...>}. */
    int run(String... args) {
        String[] full = new String[args.length + 2];
        full[0] = "--project-dir";
        full[1] = projectRoot.toString();
        System.arraycopy(args, 0, full, 2, args.length);
        return new CommandLine(new Main()).execute(full);
    }

    int runWithStdin(String stdin, String... args) {
        System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
        return run(args);
    }

    String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    void clearOutput() {
        out.reset();
        err.reset();
    }

    Path writeProjectFile(String relative, String content) throws IOException {
        Path file = projectRoot.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /** A project root with a minimal {@code pom.xml}, so module detection succeeds. */
    void createMavenProject() throws IOException {
        writeProjectFile("pom.xml", MINIMAL_POM);
    }

    static String jsonPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
