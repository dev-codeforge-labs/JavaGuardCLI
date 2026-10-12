package com.devmanchego.javaguard.build.gradle;

import com.devmanchego.javaguard.build.BuildResult;
import com.devmanchego.javaguard.build.ClasspathCache;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.project.BuildTool;
import com.devmanchego.javaguard.project.BuildToolExecutable;
import com.devmanchego.javaguard.project.BuildToolLocator;
import com.devmanchego.javaguard.project.ModuleInfo;
import com.devmanchego.javaguard.project.ProjectDetector;
import com.devmanchego.javaguard.project.ReactorModules;
import com.devmanchego.javaguard.testing.TestSelection;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Plan Phase 17, the scenarios of phases 6, 8 and 9 on real Gradle fixtures in both DSLs: classpath extraction, compile
 * errors, test failures and a dependency that does not exist. Run with {@code -Pintegration}; needs a {@code gradle} on
 * {@code PATH} (or the fixture's wrapper) and, for the first run, network access to Maven Central. Skipped - not failed -
 * where Gradle is absent, which is why these tests had not been run when the phase was written (see the plan).
 */
@Tag("integration")
class GradleFixtureIntegrationTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures").toAbsolutePath().normalize();
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final String APP = "src/main/java/com/acme/simple/App.java";
    private static final String APP_TEST = "src/test/java/com/acme/simple/AppTest.java";

    @TempDir
    Path root;

    private BuildToolExecutable gradle() {
        Optional<BuildToolExecutable> found = BuildToolLocator.gradle(root, System.getenv(), WINDOWS);
        assumeTrue(found.isPresent(), "Gradle is not installed: integration test skipped");
        return found.get();
    }

    private void copy(String fixture) throws IOException {
        Path source = FIXTURES.resolve(fixture);
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path file : (Iterable<Path>) walk::iterator) {
                Path target = root.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else if (!("/" + source.relativize(file)).replace('\\', '/').contains("/build/")) {
                    Files.copy(file, target);
                }
            }
        }
    }

    private GradleBuildRunner runner() {
        return new GradleBuildRunner(new ProcessRunner(), gradle(), ReactorModules.loadGradle(root), root,
                Optional.of(root.resolve(".javaguard/logs")), true, List.of(), System.getenv());
    }

    private void replaceIn(String relative, String from, String to) throws IOException {
        Path file = root.resolve(relative);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(text).contains(from);
        Files.writeString(file, text.replace(from, to), StandardCharsets.UTF_8);
    }

    private TestSelection wholeProject() {
        return new TestSelection(JavaGuardConfig.TestScope.MODULE, List.of(), Set.of(root), true, Set.of(), false, List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"gradle-simple", "gradle-groovy"})
    void aCleanProjectCompilesAndItsTestsPass(String fixture) throws Exception {
        copy(fixture);
        GradleBuildRunner runner = runner();

        assertThat(runner.testCompile(Set.of(root), TIMEOUT).success()).isTrue();
        BuildResult tests = runner.runTests(wholeProject(), TIMEOUT);

        assertThat(tests.success()).as(tests.findings().toString()).isTrue();
        assertThat(tests.testsRun()).isGreaterThan(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gradle-simple", "gradle-groovy"})
    void aCompileErrorIsReportedAtItsFileAndLine(String fixture) throws Exception {
        copy(fixture);
        replaceIn(APP, "return \"Hello, \" + name + \"!\";", "return undefinedVariable;");

        BuildResult result = runner().testCompile(Set.of(root), TIMEOUT);

        assertThat(result.success()).isFalse();
        assertThat(result.findings()).anySatisfy(f -> {
            assertThat(f.ruleId()).isEqualTo("JAVAC");
            assertThat(f.file()).contains(Path.of(APP));
            assertThat(f.message()).contains("cannot find symbol");
            assertThat(f.line()).isGreaterThan(0);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"gradle-simple", "gradle-groovy"})
    void aFailingTestIsReportedWithItsAssertion(String fixture) throws Exception {
        copy(fixture);
        replaceIn(APP, "\"Hello, \"", "\"Bye, \"");

        BuildResult result = runner().runTests(wholeProject(), TIMEOUT);

        assertThat(result.success()).isFalse();
        assertThat(result.findings()).anySatisfy(f -> {
            assertThat(f.ruleId()).isEqualTo("TEST");
            assertThat(f.file()).contains(Path.of(APP_TEST));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"gradle-simple", "gradle-groovy"})
    void aDependencyThatDoesNotExistIsJgDep003(String fixture) throws Exception {
        copy(fixture);
        String file = fixture.equals("gradle-simple") ? "build.gradle.kts" : "build.gradle";
        String dependency = fixture.equals("gradle-simple") ? "    implementation(\"com.javaguard.fake:nothing:9.9.9\")\n"
                : "    implementation 'com.javaguard.fake:nothing:9.9.9'\n";
        replaceIn(file, "dependencies {", "dependencies {\n" + dependency);

        BuildResult result = runner().testCompile(Set.of(root), TIMEOUT);

        assertThat(result.success()).isFalse();
        assertThat(result.findings()).anySatisfy(f -> assertThat(f.ruleId()).isEqualTo("JG-DEP-003"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gradle-simple", "gradle-groovy"})
    void theClasspathIncludesJUnitAndExcludesTheModulesOwnOutput(String fixture) throws Exception {
        copy(fixture);
        BuildToolExecutable executable = gradle();
        assertThat(executable).isNotNull();
        ModuleInfo module = ProjectDetector.detect(root, Optional.of(BuildTool.GRADLE)).orElseThrow();

        Optional<ClasspathCache> cache = new GradleClasspathExtractor().extract(module, true, List.of(), System.getenv(), WINDOWS);

        assertThat(cache).isPresent();
        assertThat(cache.get().testClasspath()).anyMatch(e -> e.contains("junit-jupiter-api"));
        assertThat(cache.get().testClasspath()).noneMatch(e -> e.replace('\\', '/').contains(root.toString().replace('\\', '/') + "/build/"));
        assertThat(cache.get().release()).isEqualTo(module.javaRelease());
    }
}
