package com.devmanchego.javaguard.build.maven;

import com.devmanchego.javaguard.build.ClasspathCache;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.project.BuildTool;
import com.devmanchego.javaguard.project.BuildToolExecutable;
import com.devmanchego.javaguard.project.BuildToolLocator;
import com.devmanchego.javaguard.project.ProjectDetector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 6: "cache built for each Maven fixture", against the real
 * fixtures under src/test/resources/fixtures (specification section 8.3).
 * Runs real {@code mvn} processes; no {@code @Tag("integration")}, matching
 * this project's own convention that a real subprocess test
 * ({@code ProcessRunnerTest}) is part of the default suite, since building
 * this very project already requires Maven on {@code PATH}.
 */
class MavenClasspathExtractorTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures").toAbsolutePath().normalize();
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final Map<String, String> ENV = System.getenv();

    private final MavenClasspathExtractor extractor = new MavenClasspathExtractor();

    /** module-b declares a real dependency on module-a; a first build must have installed it, like any real project. */
    @BeforeAll
    static void installMultimoduleReactor() throws IOException {
        Path reactorRoot = FIXTURES.resolve("maven-multimodule");
        BuildToolExecutable mvn = BuildToolLocator.maven(reactorRoot, false, ENV, WINDOWS).orElseThrow();
        List<String> command = new java.util.ArrayList<>(mvn.command());
        command.add("install");
        command.add("-DskipTests");
        command.add("-q");
        new ProcessRunner().run(command, reactorRoot, Map.of(), Duration.ofMinutes(2));
    }

    private Optional<ClasspathCache> extract(String fixtureRelativePath) {
        Path moduleRoot = FIXTURES.resolve(fixtureRelativePath);
        var module = ProjectDetector.detect(moduleRoot, Optional.of(BuildTool.MAVEN)).orElseThrow();
        return extractor.extract(module, false, true, List.of(), ENV, WINDOWS);
    }

    @Test
    void mavenSimpleGetsATestClasspathWithJunitButNotOnCompileClasspath() {
        ClasspathCache cache = extract("maven-simple").orElseThrow();

        assertThat(cache.testClasspath()).anyMatch(p -> p.contains("junit-jupiter"));
        assertThat(cache.compileClasspath()).noneMatch(p -> p.contains("junit-jupiter"));
        assertThat(cache.release()).isEqualTo(17);
        assertThat(cache.buildFileHashes()).containsKey(
                FIXTURES.resolve("maven-simple/pom.xml").toString().replace('\\', '/'));
    }

    @Test
    void moduleAWithNoDependenciesGetsAnEmptyCompileClasspath() {
        ClasspathCache cache = extract("maven-multimodule/module-a").orElseThrow();

        assertThat(cache.compileClasspath()).isEmpty();
        assertThat(cache.buildFileHashes()).containsKeys(
                FIXTURES.resolve("maven-multimodule/module-a/pom.xml").toString().replace('\\', '/'),
                FIXTURES.resolve("maven-multimodule/pom.xml").toString().replace('\\', '/'));
    }

    @Test
    void moduleBResolvesItsInterModuleDependencyOnModuleA() {
        ClasspathCache cache = extract("maven-multimodule/module-b").orElseThrow();

        assertThat(cache.compileClasspath()).anyMatch(p -> p.contains("module-a"));
        assertThat(cache.testClasspath()).anyMatch(p -> p.contains("junit-jupiter"));
    }

    @Test
    void lombokFixtureClasspathIncludesTheLombokJar() {
        ClasspathCache cache = extract("maven-spring-jpa-lombok").orElseThrow();

        assertThat(cache.compileClasspath()).anyMatch(p -> p.contains("lombok"));
        assertThat(cache.compileClasspath()).anyMatch(p -> p.contains("jakarta.persistence"));
    }

    @Test
    void buildFileChainStopsAtTheReactorRootForAModule() {
        List<Path> chain = MavenClasspathExtractor.buildFileChain(FIXTURES.resolve("maven-multimodule/module-b"));

        assertThat(chain).containsExactly(
                FIXTURES.resolve("maven-multimodule/module-b/pom.xml"),
                FIXTURES.resolve("maven-multimodule/pom.xml"));
    }
}
