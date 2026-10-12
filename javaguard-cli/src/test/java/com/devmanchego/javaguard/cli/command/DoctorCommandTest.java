package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.build.ClasspathCache;
import com.devmanchego.javaguard.build.ClasspathCacheStore;
import com.devmanchego.javaguard.build.maven.MavenClasspathExtractor;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 7: {@code doctor} (specification &sect;4). */
class DoctorCommandTest extends CommandTestBase {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    private int doctor() {
        return new DoctorCommand().run(projectRoot, null, userHome, Map.of(),
                new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    private String table() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aMavenProjectWithoutCacheOrHooksWarnsButSucceeds() throws Exception {
        createMavenProject();

        int exit = doctor();

        assertThat(exit).isZero();
        assertThat(table())
                .contains("[OK  ] Java runtime")
                .contains("[OK  ] Project")
                .contains("[WARN] Classpath cache").contains("javaguard warmup")
                .contains("[WARN] Claude Code hooks").contains("javaguard install --agent claude")
                .contains("[OK  ] Configuration");
    }

    @Test
    void consultationIsReportedDisabledByDefault() throws Exception {
        createMavenProject();

        doctor();

        assertThat(table()).contains("[OK  ] Consultation").contains("Disabled");
    }

    @Test
    void enabledConsultationWithoutAnyConsultantOnPathWarns() throws Exception {
        createMavenProject();
        Files.writeString(projectRoot.resolve("javaguard.yml"), "consult:\n  enabled: true\n");

        int exit = doctor();

        assertThat(exit).isZero();
        assertThat(table()).contains("[WARN] Consultation").contains("no consultant was found on PATH");
    }

    @Test
    void aDirectoryThatIsNotAJavaProjectIsAnEnvironmentError() {
        int exit = doctor();

        assertThat(exit).isEqualTo(4);
        assertThat(table()).contains("[FAIL] Project").contains("No pom.xml or build.gradle");
    }

    @Test
    void anInvalidConfigurationIsAConfigurationError() throws Exception {
        createMavenProject();
        Files.writeString(projectRoot.resolve("javaguard.yml"), "fastGate: { budgetMillis: 0 }\n");

        int exit = doctor();

        assertThat(exit).isEqualTo(3);
        assertThat(table()).contains("[FAIL] Configuration").contains("fastGate.budgetMillis");
    }

    @Test
    void unknownConfigurationKeysAreAWarning() throws Exception {
        createMavenProject();
        Files.writeString(projectRoot.resolve("javaguard.yml"), "nonsense: true\n");

        int exit = doctor();

        assertThat(exit).isZero();
        assertThat(table()).contains("[WARN] Configuration").contains("nonsense");
    }

    @Test
    void installedHooksAreReported() throws Exception {
        createMavenProject();
        assertThat(run("install", "--agent", "claude", "--command", "C:/tools/javaguard.exe")).isZero();
        clearOutput();

        doctor();

        assertThat(table()).contains("[OK  ] Claude Code hooks").contains("post-edit");
    }

    @Test
    void aBuiltCacheIsReportedAndAStaleOneIsNot() throws Exception {
        createMavenProject();
        JavaGuardPaths paths = new JavaGuardPaths(projectRoot);
        paths.ensureLayout();
        var module = com.devmanchego.javaguard.project.ProjectDetector.detect(projectRoot, java.util.Optional.empty()).orElseThrow();
        ClasspathCache cache = new ClasspathCache(List.of("a.jar"), List.of("a.jar"), List.of(), 21, List.of(), List.of(),
                hashesOfBuildFiles(module.moduleRoot()));
        new ClasspathCacheStore(paths.classpathCacheDir()).put(module.moduleRoot(), cache);

        doctor();
        assertThat(table()).contains("[OK  ] Classpath cache");

        captured.reset();
        Files.writeString(projectRoot.resolve("pom.xml"), MINIMAL_POM + "<!-- changed -->");
        doctor();
        assertThat(table()).contains("[WARN] Classpath cache").contains("Stale");
    }

    @Test
    void aDisabledJavaGuardIsCalledOut() throws Exception {
        createMavenProject();
        new JavaGuardPaths(projectRoot).ensureLayout();
        Files.createFile(new JavaGuardPaths(projectRoot).disabledMarkerFile());

        doctor();

        assertThat(table()).contains("[WARN] Enabled").contains("disabled");
    }

    private static Map<String, String> hashesOfBuildFiles(java.nio.file.Path moduleRoot) {
        Map<String, String> hashes = new java.util.LinkedHashMap<>();
        for (java.nio.file.Path pom : MavenClasspathExtractor.buildFileChain(moduleRoot)) {
            hashes.put(pom.toAbsolutePath().normalize().toString().replace('\\', '/'),
                    ClasspathCacheStore.hashOf(pom).orElseThrow());
        }
        return hashes;
    }
}
