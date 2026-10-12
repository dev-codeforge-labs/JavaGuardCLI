package com.devmanchego.javaguard.cli.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 20: {@code check --full} runs the end-of-turn pipeline by hand (real Maven on the simple fixture). */
class CheckFullCommandTest extends CommandTestBase {

    private static final Path FIXTURE = Path.of("../javaguard-core/src/test/resources/fixtures/maven-simple").toAbsolutePath().normalize();
    private static final String APP = "src/main/java/com/acme/simple/App.java";

    @BeforeEach
    void project() throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        System.getenv().forEach((k, v) -> {
            String upper = k.toUpperCase();
            if (!upper.startsWith("CLAUDE_") && !upper.startsWith("GEMINI_") && !upper.startsWith("CODEX_") && !upper.startsWith("JAVAGUARD_")) {
                env.put(k, v);
            }
        });
        ProcessEnvironment.overrideForTests(env);
        try (Stream<Path> walk = Files.walk(FIXTURE)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path target = projectRoot.resolve(FIXTURE.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else if (!source.toString().replace('\\', '/').contains("/target/")) {
                    Files.copy(source, target);
                }
            }
        }
    }

    @Test
    void aBreakingChangeFailsTheFullCheckWithTheTestFailureAndLeavesNoSessionBehind() throws Exception {
        Path app = projectRoot.resolve(APP);
        Files.writeString(app, Files.readString(app).replace("\"Hello, \"", "\"Hi, \""));

        int exit = run("check", "--full", app.toString());

        assertThat(exit).as(stdout() + stderr()).isEqualTo(1);
        assertThat(stdout()).contains("JAVAGUARD BLOCK").contains("[TEST Test failure]").contains("expected: <Hello, World!> but was: <Hi, World!>");
        try (Stream<Path> sessions = Files.list(projectRoot.resolve(".javaguard/state"))) {
            assertThat(sessions.filter(p -> p.getFileName().toString().startsWith("check-full-"))).isEmpty();
        }
    }

    @Test
    void aCleanChangePassesTheFullCheck() throws Exception {
        Path app = projectRoot.resolve(APP);
        Files.writeString(app, Files.readString(app).replace("public class App {", "public class App {\n    // a comment"));

        int exit = run("check", "--full", "--format", "json", app.toString());

        assertThat(exit).as(stdout() + stderr()).isZero();
        assertThat(stdout()).contains("\"verdict\"");
    }
}
