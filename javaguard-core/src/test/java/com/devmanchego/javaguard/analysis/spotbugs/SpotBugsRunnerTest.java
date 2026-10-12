package com.devmanchego.javaguard.analysis.spotbugs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 18: SpotBugs and FindSecBugs run in process on class files compiled on the fly. */
class SpotBugsRunnerTest {

    static final String SQL_INJECTION = """
            package com.acme;

            import java.sql.Connection;
            import java.sql.ResultSet;
            import java.sql.SQLException;
            import java.sql.Statement;

            public class UserDao {
                public ResultSet find(Connection connection, String name) throws SQLException {
                    Statement statement = connection.createStatement();
                    return statement.executeQuery("SELECT * FROM users WHERE name = '" + name + "'");
                }
            }
            """;

    @TempDir
    Path dir;

    /** Compiles {@code source} (class {@code com.acme.<name>}) into {@code dir/classes} and returns every class file produced. */
    static List<Path> compile(Path dir, String name, String source) throws IOException {
        Path sources = Files.createDirectories(dir.resolve("src/com/acme"));
        Path file = sources.resolve(name + ".java");
        Files.writeString(file, source);
        Path classes = Files.createDirectories(dir.resolve("classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int result = compiler.run(null, null, null, "-g", "--release", "17", "-d", classes.toString(), file.toString());
        assertThat(result).isZero();
        try (var walk = Files.walk(classes)) {
            return walk.filter(p -> p.toString().endsWith(".class")).toList();
        }
    }

    @Test
    void findSecBugsReportsASqlInjectionWithItsLine() throws Exception {
        List<Path> classes = compile(dir, "UserDao", SQL_INJECTION);

        List<SpotBugsRunner.Bug> bugs = new SpotBugsRunner().run(classes, List.of());

        assertThat(bugs).anySatisfy(bug -> {
            assertThat(bug.pattern()).startsWith("SQL_INJECTION");
            assertThat(bug.category()).isEqualTo("SECURITY");
            assertThat(bug.className()).isEqualTo("com.acme.UserDao");
            assertThat(bug.line()).isEqualTo(11);
            assertThat(bug.rank()).isBetween(1, 20);
        });
    }

    @Test
    void cleanCodeHasNoSecurityBugs() throws Exception {
        List<Path> classes = compile(dir, "Clean", "package com.acme;\npublic class Clean { public int twice(int n) { return n * 2; } }\n");

        assertThat(new SpotBugsRunner().run(classes, List.of())).noneMatch(b -> b.category().equals("SECURITY"));
    }

    @Test
    void noClassesMeansNoWork() throws Exception {
        assertThat(new SpotBugsRunner().run(List.of(), List.of())).isEmpty();
    }
}
