package com.devmanchego.javaguard.build.gradle;

import com.devmanchego.javaguard.build.ClasspathCache;
import com.devmanchego.javaguard.process.ProcessResult;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.project.BuildTool;
import com.devmanchego.javaguard.project.GradleProjects;
import com.devmanchego.javaguard.project.ModuleInfo;
import com.devmanchego.javaguard.project.ProjectDetector;
import com.devmanchego.javaguard.project.ReactorModules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Plan Phase 17: reading a Gradle build's structure and extracting a module's classpath (Gradle itself mocked). */
class GradleProjectsAndExtractorTest {

    @TempDir
    Path root;

    private Path api;
    private Path app;

    @BeforeEach
    void layout() throws IOException {
        Files.writeString(root.resolve("settings.gradle"), """
                rootProject.name = 'demo'
                // include 'commented'
                include ':api'
                include 'app', 'libs:util'
                """);
        api = Files.createDirectories(root.resolve("api"));
        app = Files.createDirectories(root.resolve("app"));
        Files.createDirectories(root.resolve("libs/util"));
        Files.writeString(api.resolve("build.gradle"), "plugins { id 'java' }\n");
        Files.writeString(root.resolve("libs/util/build.gradle.kts"), "plugins { java }\n");
        Files.writeString(app.resolve("build.gradle"), """
                plugins { id 'java' }
                dependencies {
                    implementation project(':api')
                    testImplementation(project(path: ":libs:util"))
                    // implementation project(':ghost')
                }
                """);
    }

    @Test
    void settingsIncludesAreReadInBothSyntaxesIgnoringComments() {
        assertThat(GradleProjects.includedProjects(root)).containsExactly("api", "app", "libs:util");
    }

    @Test
    void kotlinSettingsWithFunctionCallsAndMultiLineLists() throws IOException {
        Files.delete(root.resolve("settings.gradle"));
        Files.writeString(root.resolve("settings.gradle.kts"), "include(\n    \":api\",\n    \":app\"\n)\n");

        assertThat(GradleProjects.includedProjects(root)).containsExactly("api", "app");
    }

    @Test
    void projectDependenciesAreTheNamedSiblings() {
        assertThat(GradleProjects.projectDependencies(app)).containsExactlyInAnyOrder("api", "libs:util");
    }

    @Test
    void taskPrefixesFollowTheProjectPath() {
        assertThat(GradleProjects.taskPrefix(root, root)).isEqualTo(":");
        assertThat(GradleProjects.taskPrefix(root, app)).isEqualTo(":app:");
        assertThat(GradleProjects.taskPrefix(root, root.resolve("libs/util"))).isEqualTo(":libs:util:");
    }

    @Test
    void theGradleReactorKnowsItsModulesAndWhoDependsOnWhom() {
        ReactorModules reactor = ReactorModules.loadGradle(root);

        assertThat(reactor.buildTool()).isEqualTo(BuildTool.GRADLE);
        assertThat(reactor.all()).extracting(m -> m.info().moduleRoot()).containsExactlyInAnyOrder(api, app, root.resolve("libs/util"));
        assertThat(reactor.withDependents(Set.of(api))).containsExactlyInAnyOrder(api, app);
        assertThat(reactor.withDependents(Set.of(root.resolve("libs/util")))).contains(app);
        assertThat(reactor.moduleOf(app.resolve("src/main/java/A.java"))).isPresent();
    }

    // --- classpath extraction ------------------------------------------------------------------

    private ModuleInfo module() {
        return ProjectDetector.detect(app, Optional.of(BuildTool.GRADLE)).orElseThrow();
    }

    private static String line(String key, List<String> entries) {
        return key + "=" + String.join(File.pathSeparator, entries) + "\n";
    }

    @Test
    void theClasspathFileWrittenByTheInitScriptBecomesTheCache() throws Exception {
        Path gradle = Files.createFile(root.resolve("gradlew"));
        gradle.toFile().setExecutable(true);
        Path jar = root.resolve("lib/guava.jar");
        Path ownClasses = app.resolve("build/classes/java/main");
        Path siblingClasses = api.resolve("build/classes/java/main");
        ProcessRunner processes = mock(ProcessRunner.class);
        when(processes.run(any(), any(), any(), any(Duration.class))).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            String out = command.stream().filter(a -> a.startsWith("-Djavaguard.out=")).findFirst().orElseThrow()
                    .substring("-Djavaguard.out=".length());
            Files.writeString(Path.of(out), line("compile", List.of(jar.toString(), siblingClasses.toString()))
                    + line("test", List.of(jar.toString(), siblingClasses.toString(), ownClasses.toString())));
            assertThat(command).contains("--offline", "--init-script", ":app:javaguardClasspath");
            return new ProcessResult(OptionalInt.of(0), "", false, false, Duration.ZERO);
        });

        Optional<ClasspathCache> cache = new GradleClasspathExtractor(processes).extract(module(), true, List.of(), Map.of(
                "PATH", ""), false);

        assertThat(cache).isPresent();
        assertThat(cache.get().compileClasspath()).containsExactly(jar.toString(), siblingClasses.toString());
        assertThat(cache.get().testClasspath()).doesNotContain(ownClasses.toString()).contains(siblingClasses.toString());
        assertThat(cache.get().mainSourceRoots()).containsExactly(app.resolve("src/main/java").toAbsolutePath().toString());
        assertThat(cache.get().buildFileHashes().keySet()).anyMatch(k -> k.endsWith("app/build.gradle"))
                .anyMatch(k -> k.endsWith("settings.gradle"));
    }

    @Test
    void offlineFailureIsRetriedOnlineAndTotalFailureGivesNothing() throws Exception {
        Path gradle = Files.createFile(root.resolve("gradlew"));
        gradle.toFile().setExecutable(true);
        ProcessRunner processes = mock(ProcessRunner.class);
        when(processes.run(any(), any(), any(), any(Duration.class)))
                .thenReturn(new ProcessResult(OptionalInt.of(1), "offline", false, false, Duration.ZERO));

        Optional<ClasspathCache> cache = new GradleClasspathExtractor(processes).extract(module(), true, List.of(), Map.of(), false);

        assertThat(cache).isEmpty();
        org.mockito.Mockito.verify(processes, org.mockito.Mockito.times(2)).run(any(), any(), any(), any(Duration.class));
    }

    @Test
    void withoutAGradleExecutableThereIsNoCache() {
        assertThat(new GradleClasspathExtractor(mock(ProcessRunner.class)).extract(module(), true, List.of(), Map.of(), false)).isEmpty();
    }

    @Test
    void theBuildFileChainCoversModuleRootSettingsAndCatalog() throws IOException {
        Files.writeString(root.resolve("gradle.properties"), "org.gradle.jvmargs=-Xmx1g\n");
        Files.createDirectories(root.resolve("gradle"));
        Files.writeString(root.resolve("gradle/libs.versions.toml"), "[versions]\n");

        List<String> names = GradleClasspathExtractor.buildFileChain(module()).stream()
                .map(p -> root.relativize(p).toString().replace('\\', '/')).toList();

        assertThat(names).contains("app/build.gradle", "gradle.properties", "settings.gradle", "gradle/libs.versions.toml")
                .doesNotContain("api/build.gradle");
    }
}
