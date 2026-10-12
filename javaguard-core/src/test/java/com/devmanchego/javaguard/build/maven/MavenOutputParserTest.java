package com.devmanchego.javaguard.build.maven;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 8: the Maven output parser against stored samples (specification &sect;10.4). The
 * {@code maven-3.9.4-*} samples are real captures; see {@code maven-output/README.md} for the rest.
 */
class MavenOutputParserTest {

    private static final Path ROOT = Path.of("C:/work/proj").toAbsolutePath().normalize();

    private static String sample(String name) throws IOException {
        try (var in = MavenOutputParserTest.class.getResourceAsStream("/maven-output/" + name)) {
            if (in == null) {
                throw new IOException("Missing sample " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("@ROOT@", ROOT.toString().replace('\\', '/'));
        }
    }

    /** Real captures: Maven 3.8.9 (compiler plugin 3.8.1), 3.9.4 (3.11.0) and 4.0.0-rc-7 (3.16.0). */
    private static final String[] VERSIONS = {"maven-3.8.9", "maven-3.9.4", "maven-4.0.0-rc-7"};

    @ParameterizedTest
    @ValueSource(strings = {"maven-3.8.9", "maven-3.9.4", "maven-4.0.0-rc-7"})
    void aCompileErrorIsOneFindingWithSymbolAndLocationInEveryMavenVersion(String version) throws IOException {
        for (String mode : new String[] {"quiet", "verbose"}) {
            MavenOutputParser.Parsed parsed = MavenOutputParser.parse(sample(version + "-compile-error-" + mode + ".txt"), ROOT);

            assertThat(parsed.compileErrors()).as("%s %s: Maven prints it twice; the parser merges them", version, mode).hasSize(1);
            Finding f = parsed.compileErrors().get(0);
            assertThat(f.ruleId()).isEqualTo("JAVAC");
            assertThat(f.category()).isEqualTo(Category.COMPILATION);
            assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(f.file()).contains(Path.of("module-b/src/main/java/com/acme/moduleb/Bar.java"));
            assertThat(f.line()).isEqualTo(7);
            assertThat(f.column()).isEqualTo(25);
            assertThat(f.message()).isEqualTo("cannot find symbol");
            assertThat(f.detail()).containsEntry("symbol", "method value()")
                    .containsEntry("location", "class com.acme.modulea.Foo");
            assertThat(parsed.resolutionFailed()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"maven-3.8.9", "maven-3.9.4", "maven-4.0.0-rc-7"})
    void aHallucinatedDependencyIsADep003ForTheRightArtifactInEveryMavenVersion(String version) throws IOException {
        MavenOutputParser.Parsed parsed = MavenOutputParser.parse(sample(version + "-dependency-missing.txt"), ROOT);

        assertThat(parsed.resolutionFailed()).isTrue();
        assertThat(parsed.offlineFailure()).isFalse();
        assertThat(parsed.compileErrors()).isEmpty();
        assertThat(parsed.dependencyFindings()).hasSize(1);
        Finding f = parsed.dependencyFindings().get(0);
        assertThat(f.ruleId()).isEqualTo("JG-DEP-003");
        assertThat(f.severity()).isEqualTo(Severity.BLOCKER);
        assertThat(f.category()).isEqualTo(Category.DEPENDENCY);
        assertThat(f.message()).contains("org.nonexistent.hallucinated:magic-utils:jar:9.9.9");
        assertThat(f.detail()).containsEntry("artifact", "org.nonexistent.hallucinated:magic-utils:jar:9.9.9");
        assertThat(f.detail()).as("%s: the failing project", version).containsEntry("project", "com.acme:module-b:jar:1.0.0");
    }

    @Test
    void theSecondRunReportsTheFailureMavenCachedAndStillNamesTheArtifact() {
        // Real output (Maven 3.9.16, Linux) of a second build of a project whose dependency does not exist.
        String output = """
                [ERROR] Failed to execute goal on project a: Could not resolve dependencies for project g:a:jar:1
                [ERROR] dependency: org.nonexistent.hallucinated:magic-utils:jar:9.9.9 (compile)
                [ERROR] 	org.nonexistent.hallucinated:magic-utils:jar:9.9.9 was not found in https://repo.maven.apache.org/maven2 during a previous attempt. This failure was cached in the local repository and resolution is not reattempted until the update interval of central has elapsed or updates are forced
                [ERROR] -> [Help 1]
                """;

        MavenOutputParser.Parsed parsed = MavenOutputParser.parse(output, ROOT);

        assertThat(parsed.dependencyFindings()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("JG-DEP-003");
            assertThat(f.message()).contains("org.nonexistent.hallucinated:magic-utils:jar:9.9.9");
        });
        assertThat(parsed.offlineFailure()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"maven-3.8.9", "maven-3.9.4"})
    void maven3SaysItWasOfflineSoTheRunnerKnowsToRetryOnline(String version) throws IOException {
        MavenOutputParser.Parsed parsed = MavenOutputParser.parse(sample(version + "-dependency-offline.txt"), ROOT);

        assertThat(parsed.resolutionFailed()).isTrue();
        assertThat(parsed.offlineFailure()).isTrue();
        assertThat(parsed.dependencyFindings()).as("offline says nothing about existence").isEmpty();
    }

    @Test
    void maven4GivesTheSameMessageOfflineAsOnlineSoOnlyAnOnlineRunCanProveAnArtifactMissing() throws IOException {
        String offline = sample("maven-4.0.0-rc-7-dependency-offline.txt");
        String online = sample("maven-4.0.0-rc-7-dependency-missing.txt");

        assertThat(MavenOutputParser.parse(offline, ROOT).offlineFailure()).isFalse();
        assertThat(MavenOutputParser.parse(offline, ROOT).resolutionFailed()).isTrue();
        // The parser cannot tell them apart, which is exactly why the runner re-runs online before reporting.
        assertThat(MavenOutputParser.parse(offline, ROOT).dependencyFindings())
                .isEqualTo(MavenOutputParser.parse(online, ROOT).dependencyFindings());
    }

    @ParameterizedTest
    @ValueSource(strings = {"maven-3.8.9", "maven-3.9.4", "maven-4.0.0-rc-7"})
    void aProjectMavenCannotSelectIsRecognizedInEveryMavenVersion(String version) throws IOException {
        MavenOutputParser.Parsed parsed = MavenOutputParser.parse(sample(version + "-selection-failure.txt"), ROOT);

        assertThat(parsed.selectionFailed()).isTrue();
        assertThat(parsed.compileErrors()).isEmpty();
        assertThat(parsed.resolutionFailed()).isFalse();
    }

    @Test
    void severalErrorsInOneFileStayDistinct() throws IOException {
        MavenOutputParser.Parsed parsed = MavenOutputParser.parse(sample("synthetic-two-errors-posix-paths.txt"), ROOT);

        assertThat(parsed.compileErrors()).extracting(Finding::message)
                .containsExactly("cannot find symbol", "incompatible types: java.lang.String cannot be converted to int");
        assertThat(parsed.compileErrors().get(0).detail()).containsEntry("symbol", "method value()");
        assertThat(parsed.compileErrors().get(1).line()).isEqualTo(12);
    }

    @Test
    void ansiColoursDoNotHideTheDiagnostic() throws IOException {
        MavenOutputParser.Parsed parsed = MavenOutputParser.parse(sample("synthetic-ansi-colours.txt"), ROOT);

        assertThat(parsed.compileErrors()).hasSize(1);
        assertThat(parsed.compileErrors().get(0).detail()).containsEntry("symbol", "method value()");
    }

    @Test
    void windowsStyleLeadingSlashBeforeTheDriveIsNormalized() {
        assertThat(MavenOutputParser.normalizePath("/C:/work/proj/Foo.java")).isEqualTo("C:/work/proj/Foo.java");
        assertThat(MavenOutputParser.normalizePath("/home/dev/Foo.java")).isEqualTo("/home/dev/Foo.java");
        assertThat(MavenOutputParser.normalizePath("C:\\work\\Foo.java")).isEqualTo("C:\\work\\Foo.java");
    }

    @Test
    void sameDiagnosticGetsTheSameFingerprintAsTheInProcessCompilerWouldGiveIt() throws IOException {
        Finding f = MavenOutputParser.parse(sample("maven-3.9.4-compile-error-quiet.txt"), ROOT).compileErrors().get(0);

        assertThat(f.fingerprint()).isEqualTo(com.devmanchego.javaguard.change.Fingerprints.of("JAVAC",
                "module-b/src/main/java/com/acme/moduleb/Bar.java", "cannot find symbol"));
    }

    @Test
    void unreachableRepositoryIsNeverReportedAsAHallucinatedDependency() {
        String output = "[ERROR] Failed to execute goal on project x: Could not resolve dependencies for project g:x:jar:1: "
                + "Could not transfer artifact org.foo:bar:pom:1.0 from/to central (https://repo.maven.apache.org/maven2): "
                + "Transfer failed for https://repo.maven.apache.org/maven2/org/foo/bar/1.0/bar-1.0.pom: UnknownHostException\n";

        MavenOutputParser.Parsed parsed = MavenOutputParser.parse(output, ROOT);

        assertThat(parsed.transferFailure()).isTrue();
        assertThat(parsed.dependencyFindings()).isEmpty();
    }

    @Test
    void unclassifiedFailureKeepsTheLastRelevantLinesWithoutTheHelpFooter() {
        StringBuilder out = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            out.append("[ERROR] plugin problem line ").append(i).append('\n');
        }
        out.append("[ERROR] -> [Help 1]\n[ERROR] \n[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.\n");

        MavenOutputParser.Parsed parsed = MavenOutputParser.parse(out.toString(), ROOT);

        assertThat(parsed.tail()).hasSize(MavenOutputParser.TAIL_LINES);
        assertThat(parsed.tail().get(parsed.tail().size() - 1)).isEqualTo("[ERROR] plugin problem line 40");
        assertThat(parsed.compileErrors()).isEmpty();
        assertThat(parsed.dependencyFindings()).isEmpty();
    }

    @Test
    void buildFailureIsAnInfrastructureFindingThatCannotBlock() {
        Finding f = MavenOutputParser.buildFailure("Maven", java.util.List.of("[ERROR] OutOfMemoryError: Java heap space"));

        assertThat(f.category()).isEqualTo(Category.INFRASTRUCTURE);
        assertThat(f.message()).contains("OutOfMemoryError");
    }

    @Test
    void cleanOutputYieldsNothing() {
        MavenOutputParser.Parsed parsed = MavenOutputParser.parse("", ROOT);

        assertThat(parsed.compileErrors()).isEmpty();
        assertThat(parsed.dependencyFindings()).isEmpty();
        assertThat(parsed.resolutionFailed()).isFalse();
    }
}
