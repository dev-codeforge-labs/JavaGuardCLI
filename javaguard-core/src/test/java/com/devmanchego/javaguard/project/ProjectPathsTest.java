package com.devmanchego.javaguard.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Found by the first CI run: on macOS ({@code /var} is {@code /private/var}) and Windows (8.3 short names) a compile error at
 * the path the build tool printed looked like it was outside the project, and was reported as just {@code Bar.java}.
 */
class ProjectPathsTest {

    @TempDir
    Path dir;

    private Path link(Path target) throws IOException {
        Path link = dir.resolve("link");
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "symbolic links are not available here: " + e);
            return link;
        }
    }

    @Test
    void aPathUnderTheRootIsRelativeToIt() throws IOException {
        Path root = Files.createDirectories(dir.resolve("proj"));

        assertThat(ProjectPaths.relativeTo(root, root.resolve("src/A.java"))).contains(Path.of("src/A.java"));
        assertThat(ProjectPaths.relativeTo(root, root)).isEmpty();
        assertThat(ProjectPaths.relativeTo(root, dir.resolve("other/B.java"))).isEmpty();
    }

    @Test
    void theBuildToolsRealSpellingIsRecognisedWhenTheRootIsReachedThroughALink() throws IOException {
        Path real = Files.createDirectories(dir.resolve("real/proj"));
        Files.createDirectories(real.resolve("module-b/src"));
        Files.writeString(real.resolve("module-b/src/Bar.java"), "class Bar {}");
        Path root = link(dir.resolve("real"));   // the project as the agent names it: .../link/proj

        Path viaLink = root.resolve("proj");
        Path asPrinted = real.toRealPath().resolve("module-b/src/Bar.java");

        assertThat(ProjectPaths.relativeTo(viaLink, asPrinted)).contains(Path.of("module-b/src/Bar.java"));
        assertThat(ProjectPaths.isInside(viaLink.resolve("module-b"), asPrinted)).isTrue();
        assertThat(ProjectPaths.isInside(viaLink.resolve("module-c"), asPrinted)).isFalse();
    }

    @Test
    void aFileThatDoesNotExistYetIsResolvedThroughItsExistingAncestor() throws IOException {
        Path real = Files.createDirectories(dir.resolve("real/proj"));
        Path root = link(dir.resolve("real")).resolve("proj");

        assertThat(ProjectPaths.relativeTo(root, real.toRealPath().resolve("target/classes/New.class")))
                .contains(Path.of("target/classes/New.class"));
    }
}
