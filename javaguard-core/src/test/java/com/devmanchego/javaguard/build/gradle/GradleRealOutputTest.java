package com.devmanchego.javaguard.build.gradle;

import com.devmanchego.javaguard.build.maven.MavenOutputParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 17: {@link GradleOutputParser} against output captured from a real Gradle 8.14.3 (see the README next to the samples). */
class GradleRealOutputTest {

    @TempDir
    Path root;

    private String sample(String name) throws IOException {
        try (var in = getClass().getResourceAsStream("/gradle-output/gradle-8.14.3-" + name + ".txt")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("@ROOT@", root.toString());
            // The samples were captured on Windows: their paths use backslashes, which are not separators elsewhere.
            return java.io.File.separatorChar == '/' ? text.replace('\\', '/') : text;
        }
    }

    @Test
    void theCompileErrorPrintedTwiceIsOneFinding() throws IOException {
        MavenOutputParser.Parsed parsed = GradleOutputParser.parse(sample("compile-error"), root);

        assertThat(parsed.compileErrors()).singleElement().satisfies(f -> {
            assertThat(f.file()).contains(Path.of("src/main/java/com/acme/simple/App.java"));
            assertThat(f.line()).isEqualTo(5);
            assertThat(f.column()).isEqualTo(16);
            assertThat(f.message()).isEqualTo("cannot find symbol");
            assertThat(f.detail()).containsEntry("symbol", "variable undefinedVariable").containsEntry("location", "class App");
        });
        assertThat(parsed.resolutionFailed()).isFalse();
    }

    @Test
    void aMissingArtifactOnlineIsJgDep003() throws IOException {
        MavenOutputParser.Parsed parsed = GradleOutputParser.parse(sample("dependency-missing"), root);

        assertThat(parsed.dependencyFindings()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("JG-DEP-003");
            assertThat(f.detail()).containsEntry("artifact", "com.javaguard.fake:nothing:9.9.9");
        });
        assertThat(parsed.offlineFailure()).isFalse();
    }

    @Test
    void theOfflineMissIsNotReportedAsAMissingArtifact() throws IOException {
        MavenOutputParser.Parsed parsed = GradleOutputParser.parse(sample("dependency-offline"), root);

        assertThat(parsed.offlineFailure()).isTrue();
        assertThat(parsed.resolutionFailed()).isTrue();
        assertThat(parsed.dependencyFindings()).isEmpty();
    }

    @Test
    void anUnknownProjectInATaskPathIsASelectionFailure() throws IOException {
        MavenOutputParser.Parsed parsed = GradleOutputParser.parse(sample("selection-failure"), root);

        assertThat(parsed.selectionFailed()).isTrue();
        assertThat(parsed.tail()).first().asString().contains("Cannot locate tasks that match ':nosuch:testClasses'");
    }
}
