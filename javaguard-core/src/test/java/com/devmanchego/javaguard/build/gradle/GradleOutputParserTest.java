package com.devmanchego.javaguard.build.gradle;

import com.devmanchego.javaguard.build.maven.MavenOutputParser;
import com.devmanchego.javaguard.model.Finding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 17. The samples are written by hand from Gradle's documented message formats - no Gradle distribution
 * was available to capture real output, unlike the Maven samples - so they are the first thing to compare with a
 * real run (see the integration tests).
 */
class GradleOutputParserTest {

    @TempDir
    Path root;

    private static final String COMPILE_ERROR = """
            %s:5: error: cannot find symbol
                    return foo;
                           ^
              symbol:   variable foo
              location: class App
            %s:9: error: incompatible types: String cannot be converted to int
                    int n = "x";
                            ^
            2 errors

            FAILURE: Build failed with an exception.

            * What went wrong:
            Execution failed for task ':compileJava'.
            > Compilation failed; see the compiler error output for details.

            * Try:
            > Run with --stacktrace option to get the stack trace.
            """;

    @Test
    void javacDiagnosticsBecomeOneFindingEachWithColumnAndDetails() {
        String file = root.resolve("src/main/java/com/acme/App.java").toString();

        MavenOutputParser.Parsed parsed = GradleOutputParser.parse(COMPILE_ERROR.formatted(file, file), root);

        assertThat(parsed.compileErrors()).hasSize(2);
        Finding first = parsed.compileErrors().get(0);
        assertThat(first.ruleId()).isEqualTo("JAVAC");
        assertThat(first.engine()).isEqualTo("gradle");
        assertThat(first.file()).contains(Path.of("src/main/java/com/acme/App.java"));
        assertThat(first.line()).isEqualTo(5);
        assertThat(first.column()).isEqualTo(16);
        assertThat(first.message()).isEqualTo("cannot find symbol");
        assertThat(first.detail()).containsEntry("symbol", "variable foo").containsEntry("location", "class App");
        assertThat(parsed.compileErrors().get(1).message()).startsWith("incompatible types");
        assertThat(parsed.resolutionFailed()).isFalse();
    }

    @Test
    void theSameDiagnosticPrintedTwiceIsOneFinding() {
        String file = root.resolve("A.java").toString();
        String twice = COMPILE_ERROR.formatted(file, file) + "\n" + COMPILE_ERROR.formatted(file, file);

        assertThat(GradleOutputParser.parse(twice, root).compileErrors()).hasSize(2);
    }

    @Test
    void anUnreachableOnlineRepositoryIsNeverAHallucinatedDependency() {
        String output = """
                FAILURE: Build failed with an exception.

                * What went wrong:
                Execution failed for task ':compileJava'.
                > Could not resolve all files for configuration ':compileClasspath'.
                   > Could not resolve com.acme:lib:1.0.
                     Required by:
                         project :
                      > Could not GET 'https://repo.maven.apache.org/maven2/com/acme/lib/1.0/lib-1.0.pom'.
                         > repo.maven.apache.org: Unknown host
                """;

        MavenOutputParser.Parsed parsed = GradleOutputParser.parse(output, root);

        assertThat(parsed.resolutionFailed()).isTrue();
        assertThat(parsed.transferFailure()).isTrue();
        assertThat(parsed.dependencyFindings()).isEmpty();
    }

    @Test
    void anArtifactMissingFromAReachableRepositoryIsJgDep003() {
        String output = """
                * What went wrong:
                Execution failed for task ':compileJava'.
                > Could not resolve all files for configuration ':compileClasspath'.
                   > Could not find com.fake:nothing:9.9.9.
                     Searched in the following locations:
                       - https://repo.maven.apache.org/maven2/com/fake/nothing/9.9.9/nothing-9.9.9.pom
                     Required by:
                         project :
                """;

        MavenOutputParser.Parsed parsed = GradleOutputParser.parse(output, root);

        assertThat(parsed.dependencyFindings()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("JG-DEP-003");
            assertThat(f.detail()).containsEntry("artifact", "com.fake:nothing:9.9.9").containsEntry("configuration", ":compileClasspath");
            assertThat(f.engine()).isEqualTo("gradle");
        });
        assertThat(parsed.offlineFailure()).isFalse();
    }

    @Test
    void anOfflineMissIsRetriedOnlineAndNeverReportedAsAMissingArtifact() {
        String output = """
                * What went wrong:
                Execution failed for task ':compileJava'.
                > Could not resolve all files for configuration ':compileClasspath'.
                   > Could not resolve org.slf4j:slf4j-api:2.0.13.
                     Required by:
                         project :
                      > No cached version of org.slf4j:slf4j-api:2.0.13 available for offline mode.
                """;

        MavenOutputParser.Parsed parsed = GradleOutputParser.parse(output, root);

        assertThat(parsed.offlineFailure()).isTrue();
        assertThat(parsed.resolutionFailed()).isTrue();
        assertThat(parsed.dependencyFindings()).isEmpty();
    }

    @Test
    void anUnknownProjectOrTaskIsASelectionFailure() {
        assertThat(GradleOutputParser.parse("* What went wrong:\nProject 'module-x' not found in root project 'demo'.\n", root)
                .selectionFailed()).isTrue();
        assertThat(GradleOutputParser.parse("* What went wrong:\nTask 'testClasses' not found in project ':docs'.\n", root)
                .selectionFailed()).isTrue();
    }

    @Test
    void anythingElseKeepsTheWhatWentWrongSectionAsTheTail() {
        String output = """
                Starting a Gradle Daemon
                FAILURE: Build failed with an exception.

                * What went wrong:
                A problem occurred evaluating root project 'demo'.
                > Could not find method foo() for arguments [] on root project 'demo'.

                * Try:
                > Run with --stacktrace option to get the stack trace.
                """;

        MavenOutputParser.Parsed parsed = GradleOutputParser.parse(output, root);

        assertThat(parsed.tail()).containsExactly("A problem occurred evaluating root project 'demo'.",
                "> Could not find method foo() for arguments [] on root project 'demo'.");
        assertThat(parsed.compileErrors()).isEmpty();
        assertThat(parsed.resolutionFailed()).isFalse();
    }

    @Test
    void ansiColoursAreIgnored() {
        String file = root.resolve("A.java").toString();
        String coloured = "\u001B[31m" + file + ":3: error: ';' expected\u001B[0m\n        int x\n             ^\n1 error\n";

        assertThat(GradleOutputParser.parse(coloured, root).compileErrors()).singleElement()
                .satisfies(f -> assertThat(f.message()).isEqualTo("';' expected"));
    }
}
