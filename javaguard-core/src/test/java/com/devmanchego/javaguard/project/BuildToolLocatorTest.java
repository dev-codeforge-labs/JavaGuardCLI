package com.devmanchego.javaguard.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Specification section 7 rule 3: wrapper > daemon > PATH, Windows-safe. */
class BuildToolLocatorTest {

    @Test
    void mavenPrefersTheWrapperOverEverythingElse(@TempDir Path reactorRoot, @TempDir Path pathDir) throws IOException {
        Files.createFile(reactorRoot.resolve("mvnw.cmd"));
        makeExecutable(pathDir, "mvn.cmd");
        Map<String, String> env = Map.of("PATH", pathDir.toString());

        Optional<BuildToolExecutable> found = BuildToolLocator.maven(reactorRoot, true, env, true);

        assertThat(found).isPresent();
        assertThat(found.get().source()).isEqualTo("wrapper");
        assertThat(found.get().command()).containsExactly(reactorRoot.resolve("mvnw.cmd").toString());
    }

    @Test
    void mavenUsesTheUnixWrapperNameOnNonWindows(@TempDir Path reactorRoot) throws IOException {
        Files.createFile(reactorRoot.resolve("mvnw"));

        Optional<BuildToolExecutable> found = BuildToolLocator.maven(reactorRoot, false, Map.of(), false);

        assertThat(found).isPresent();
        assertThat(found.get().command()).containsExactly(reactorRoot.resolve("mvnw").toString());
    }

    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // names a .cmd on a case-insensitive filesystem
    @Test
    void mavenPrefersDaemonOverPlainMvnWhenEnabled(@TempDir Path reactorRoot, @TempDir Path pathDir) throws IOException {
        makeExecutable(pathDir, "mvnd.cmd");
        makeExecutable(pathDir, "mvn.cmd");
        Map<String, String> env = Map.of("PATH", pathDir.toString());

        Optional<BuildToolExecutable> found = BuildToolLocator.maven(reactorRoot, true, env, true);

        assertThat(found).isPresent();
        assertThat(found.get().source()).isEqualTo("daemon");
        // PATHEXT-driven suffix casing (".CMD") may differ from the file's own name
        // casing on Windows' case-insensitive filesystem; both refer to the same file.
        assertThat(found.get().command()).singleElement().asString().isEqualToIgnoringCase(pathDir.resolve("mvnd.cmd").toString());
    }

    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // names a .cmd on a case-insensitive filesystem
    @Test
    void mavenSkipsDaemonWhenDisabled(@TempDir Path reactorRoot, @TempDir Path pathDir) throws IOException {
        makeExecutable(pathDir, "mvnd.cmd");
        makeExecutable(pathDir, "mvn.cmd");
        Map<String, String> env = Map.of("PATH", pathDir.toString());

        Optional<BuildToolExecutable> found = BuildToolLocator.maven(reactorRoot, false, env, true);

        assertThat(found).isPresent();
        assertThat(found.get().source()).isEqualTo("path");
        assertThat(found.get().command()).singleElement().asString().isEqualToIgnoringCase(pathDir.resolve("mvn.cmd").toString());
    }

    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // names a .cmd on a case-insensitive filesystem
    @Test
    void mavenFallsBackToPlainMvnOnPath(@TempDir Path reactorRoot, @TempDir Path pathDir) throws IOException {
        makeExecutable(pathDir, "mvn.cmd");
        Map<String, String> env = Map.of("PATH", pathDir.toString());

        Optional<BuildToolExecutable> found = BuildToolLocator.maven(reactorRoot, true, env, true);

        assertThat(found).isPresent();
        assertThat(found.get().source()).isEqualTo("path");
    }

    @Test
    void mavenIsEmptyWhenNothingIsFound(@TempDir Path reactorRoot) {
        assertThat(BuildToolLocator.maven(reactorRoot, true, Map.of("PATH", ""), true)).isEmpty();
    }

    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // names a .cmd on a case-insensitive filesystem
    @Test
    void gradleFallsBackToPathWhenNoWrapper(@TempDir Path reactorRoot, @TempDir Path pathDir) throws IOException {
        makeExecutable(pathDir, "gradle.bat");
        Map<String, String> env = Map.of("PATH", pathDir.toString());

        Optional<BuildToolExecutable> found = BuildToolLocator.gradle(reactorRoot, env, true);

        assertThat(found).isPresent();
        assertThat(found.get().source()).isEqualTo("path");
    }

    @Test
    void gradleUsesTheWrapperWhenPresent(@TempDir Path reactorRoot) throws IOException {
        Files.createFile(reactorRoot.resolve("gradlew.bat"));

        Optional<BuildToolExecutable> found = BuildToolLocator.gradle(reactorRoot, Map.of(), true);

        assertThat(found).isPresent();
        assertThat(found.get().source()).isEqualTo("wrapper");
    }

    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // names a .cmd on a case-insensitive filesystem
    @Test
    void pathLookupHonorsCustomPathext(@TempDir Path pathDir) throws IOException {
        Files.createFile(pathDir.resolve("mvn.ps1"));
        Map<String, String> env = Map.of("PATH", pathDir.toString(), "PATHEXT", ".PS1");

        assertThat(BuildToolLocator.findOnPath("mvn", env, true)).contains(pathDir.resolve("mvn.ps1"));
    }

    /**
     * A real Maven distribution's {@code bin/} ships both {@code mvn} (a Unix
     * shell script, not launchable by {@code CreateProcess}) and
     * {@code mvn.cmd} side by side; on Windows the extensionless one must
     * never be preferred just because it happens to sort first.
     */
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // names a .cmd on a case-insensitive filesystem
    @Test
    void pathLookupPrefersThePathextSuffixOverABareExtensionlessFileOnWindows(@TempDir Path pathDir) throws IOException {
        Files.createFile(pathDir.resolve("mvn"));
        makeExecutable(pathDir, "mvn.cmd");
        Map<String, String> env = Map.of("PATH", pathDir.toString());

        assertThat(BuildToolLocator.findOnPath("mvn", env, true))
                .isPresent().get().asString().isEqualToIgnoringCase(pathDir.resolve("mvn.cmd").toString());
    }

    @Test
    void pathLookupStillMatchesABareNameGivenItsOwnExtensionOnWindows(@TempDir Path pathDir) throws IOException {
        makeExecutable(pathDir, "tool.exe");
        Map<String, String> env = Map.of("PATH", pathDir.toString());

        assertThat(BuildToolLocator.findOnPath("tool.exe", env, true)).contains(pathDir.resolve("tool.exe"));
    }

    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // names a .cmd on a case-insensitive filesystem
    @Test
    void pathLookupIsCaseInsensitiveForEnvironmentVariableNames(@TempDir Path pathDir) throws IOException {
        makeExecutable(pathDir, "mvn.cmd");
        Map<String, String> env = Map.of("Path", pathDir.toString());

        assertThat(BuildToolLocator.findOnPath("mvn", env, true)).isPresent();
    }

    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // names a .cmd on a case-insensitive filesystem
    @Test
    void pathLookupTriesEachDirectoryInOrder(@TempDir Path first, @TempDir Path second) throws IOException {
        makeExecutable(second, "mvn.cmd");
        String path = first + java.io.File.pathSeparator + second;

        assertThat(BuildToolLocator.findOnPath("mvn", Map.of("PATH", path), true))
                .contains(second.resolve("mvn.cmd"));
    }

    @Test
    void pathLookupIsEmptyWhenPathVariableIsAbsent() {
        assertThat(BuildToolLocator.findOnPath("mvn", Map.of(), true)).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void onUnixOnlyAnExecutableFileIsACommand(@TempDir Path first, @TempDir Path second) throws IOException {
        Files.createFile(first.resolve("mvn")); // not executable: skipped
        makeExecutable(second, "mvn");
        String path = first + java.io.File.pathSeparator + second;

        assertThat(BuildToolLocator.findOnPath("mvn", Map.of("PATH", path), false)).contains(second.resolve("mvn"));
        assertThat(BuildToolLocator.findOnPath("mvn", Map.of("PATH", first.toString()), false)).isEmpty();
    }

    private static void makeExecutable(Path dir, String name) throws IOException {
        Path file = dir.resolve(name);
        Files.createFile(file);
        file.toFile().setExecutable(true);
    }
}
