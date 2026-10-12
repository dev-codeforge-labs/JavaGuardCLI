package com.devmanchego.javaguard.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Specification section 7, against the real fixture projects under src/test/resources/fixtures. */
class ProjectDetectorTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures").toAbsolutePath().normalize();

    // --- module root ---------------------------------------------------

    @Test
    void moduleRootIsTheDeepestAncestorWithABuildFile() {
        Path file = FIXTURES.resolve("maven-simple/src/main/java/com/acme/simple/App.java");

        assertThat(ProjectDetector.findModuleRoot(file)).contains(FIXTURES.resolve("maven-simple"));
    }

    @Test
    void moduleRootStopsAtTheNearestModuleNotTheReactorRoot() {
        Path file = FIXTURES.resolve("maven-multimodule/module-b/src/main/java/com/acme/moduleb/Bar.java");

        assertThat(ProjectDetector.findModuleRoot(file)).contains(FIXTURES.resolve("maven-multimodule/module-b"));
    }

    @Test
    void moduleRootAcceptsADirectoryArgument() {
        assertThat(ProjectDetector.findModuleRoot(FIXTURES.resolve("maven-simple/src/main/java")))
                .contains(FIXTURES.resolve("maven-simple"));
    }

    @Test
    void moduleRootIsEmptyOutsideAnyProject(@TempDir Path outside) {
        assertThat(ProjectDetector.findModuleRoot(outside.resolve("Loose.java"))).isEmpty();
    }

    @Test
    void moduleRootWorksForANonExistentFileUnderAnExistingModule() {
        Path notYetWritten = FIXTURES.resolve("maven-simple/src/main/java/com/acme/simple/NewClass.java");

        assertThat(ProjectDetector.findModuleRoot(notYetWritten)).contains(FIXTURES.resolve("maven-simple"));
    }

    // --- build tool ------------------------------------------------------

    @Test
    void detectsMavenFromPomXml() {
        BuildToolDetection d = ProjectDetector.detectBuildTool(FIXTURES.resolve("maven-simple"), Optional.empty());

        assertThat(d.tool()).contains(BuildTool.MAVEN);
        assertThat(d.ambiguous()).isFalse();
    }

    @Test
    void detectsGradleFromBuildGradleKts(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("build.gradle.kts"), "");

        BuildToolDetection d = ProjectDetector.detectBuildTool(dir, Optional.empty());

        assertThat(d.tool()).contains(BuildTool.GRADLE);
    }

    @Test
    void noBuildFileYieldsEmptyDetection(@TempDir Path dir) {
        assertThat(ProjectDetector.detectBuildTool(dir, Optional.empty()).tool()).isEmpty();
    }

    @Test
    void bothBuildFilesIsAmbiguousAndDefaultsToMaven(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Files.writeString(dir.resolve("build.gradle"), "");

        BuildToolDetection auto = ProjectDetector.detectBuildTool(dir, Optional.empty());
        assertThat(auto.tool()).contains(BuildTool.MAVEN);
        assertThat(auto.ambiguous()).isTrue();

        BuildToolDetection forced = ProjectDetector.detectBuildTool(dir, Optional.of(BuildTool.GRADLE));
        assertThat(forced.tool()).contains(BuildTool.GRADLE);
        assertThat(forced.ambiguous()).isTrue();
    }

    // --- reactor root ------------------------------------------------------

    @Test
    void singleModuleReactorRootIsItself() {
        Path root = FIXTURES.resolve("maven-simple");

        assertThat(ProjectDetector.findReactorRoot(root, BuildTool.MAVEN)).isEqualTo(root);
    }

    @Test
    void multiModuleReactorRootClimbsToTheParentListingTheChild() {
        Path moduleB = FIXTURES.resolve("maven-multimodule/module-b");

        assertThat(ProjectDetector.findReactorRoot(moduleB, BuildTool.MAVEN))
                .isEqualTo(FIXTURES.resolve("maven-multimodule"));
    }

    @Test
    void reactorRootDoesNotClimbPastAnUnlistedParent(@TempDir Path outer) throws IOException {
        Path parent = outer.resolve("parent");
        Path child = parent.resolve("child");
        Files.createDirectories(child);
        Files.writeString(parent.resolve("pom.xml"), """
                <project><modules><module>some-other-dir</module></modules></project>
                """);
        Files.writeString(child.resolve("pom.xml"), """
                <project><parent><groupId>g</groupId><artifactId>a</artifactId><version>1</version></parent></project>
                """);

        // parent's <modules> does not list "child", so the climb must stop at child itself.
        assertThat(ProjectDetector.findReactorRoot(child, BuildTool.MAVEN)).isEqualTo(child);
    }

    @Test
    void gradleReactorRootIsNearestAncestorWithSettingsGradle(@TempDir Path root) throws IOException {
        Path sub = root.resolve("libs/foo");
        Files.createDirectories(sub);
        Files.writeString(root.resolve("settings.gradle.kts"), "");
        Files.writeString(sub.resolve("build.gradle"), "");

        assertThat(ProjectDetector.findReactorRoot(sub, BuildTool.GRADLE)).isEqualTo(root);
    }

    @Test
    void gradleReactorRootIsItselfWithoutASettingsFile(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("build.gradle"), "");

        assertThat(ProjectDetector.findReactorRoot(dir, BuildTool.GRADLE)).isEqualTo(dir);
    }

    // --- source roots ------------------------------------------------------

    @Test
    void defaultSourceRootsForAPlainMavenModule() {
        Path root = FIXTURES.resolve("maven-simple");

        assertThat(ProjectDetector.mainSourceRoots(root, BuildTool.MAVEN)).containsExactly(root.resolve("src/main/java"));
        assertThat(ProjectDetector.testSourceRoots(root, BuildTool.MAVEN)).containsExactly(root.resolve("src/test/java"));
    }

    @Test
    void customSourceDirectoryOverrideIsHonored() {
        Path root = FIXTURES.resolve("legacy-dirty");

        assertThat(ProjectDetector.mainSourceRoots(root, BuildTool.MAVEN))
                .containsExactly(root.resolve("legacy-src/main/java"));
        assertThat(ProjectDetector.testSourceRoots(root, BuildTool.MAVEN))
                .containsExactly(root.resolve("legacy-src/test/java"));
    }

    @Test
    void gradleSourceRootsDefaultOnly(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("build.gradle"), "sourceSets { main { java { srcDirs = ['custom'] } } }");

        // Documented limitation (specification section 7 rule 4): custom Gradle sourceSets
        // are not parsed until Phase 17; defaults are used instead of failing.
        assertThat(ProjectDetector.mainSourceRoots(dir, BuildTool.GRADLE)).containsExactly(dir.resolve("src/main/java"));
    }

    // --- java release ------------------------------------------------------

    @Test
    void javaReleaseFromOwnProperty() {
        assertThat(ProjectDetector.javaRelease(FIXTURES.resolve("maven-simple"), FIXTURES.resolve("maven-simple"), BuildTool.MAVEN))
                .isEqualTo(17);
    }

    @Test
    void javaReleaseFromCompilerPluginConfiguration() {
        Path root = FIXTURES.resolve("maven-plugin-release");

        assertThat(ProjectDetector.javaRelease(root, root, BuildTool.MAVEN)).isEqualTo(11);
    }

    @Test
    void javaReleaseInheritedFromReactorRootWhenModuleHasNone() {
        Path reactor = FIXTURES.resolve("maven-multimodule");
        Path moduleA = reactor.resolve("module-a");

        assertThat(ProjectDetector.javaRelease(moduleA, reactor, BuildTool.MAVEN)).isEqualTo(21);
    }

    @Test
    void javaReleaseOldStyleSourceTargetIsNormalized() {
        Path root = FIXTURES.resolve("legacy-dirty");

        assertThat(ProjectDetector.javaRelease(root, root, BuildTool.MAVEN)).isEqualTo(8);
    }

    @Test
    void javaReleaseDefaultsTo21WhenNothingIsDeclared(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");

        assertThat(ProjectDetector.javaRelease(dir, dir, BuildTool.MAVEN)).isEqualTo(21);
    }

    @Test
    void gradleReleaseFromLanguageVersion(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("build.gradle.kts"), """
                java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
                """);

        assertThat(ProjectDetector.javaRelease(dir, dir, BuildTool.GRADLE)).isEqualTo(21);
    }

    @Test
    void gradleReleaseFromVersionEnumWithUnderscore(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("build.gradle"), "sourceCompatibility = JavaVersion.VERSION_1_8\n");

        assertThat(ProjectDetector.javaRelease(dir, dir, BuildTool.GRADLE)).isEqualTo(8);
    }

    @Test
    void gradleReleaseFromQuotedCompatibilityString(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("build.gradle"), "sourceCompatibility = '17'\n");

        assertThat(ProjectDetector.javaRelease(dir, dir, BuildTool.GRADLE)).isEqualTo(17);
    }

    @Test
    void gradleReleaseDefaultsWhenBuildFileSaysNothing(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("build.gradle"), "plugins { id 'java' }\n");

        assertThat(ProjectDetector.javaRelease(dir, dir, BuildTool.GRADLE)).isEqualTo(21);
    }

    // --- frameworks ------------------------------------------------------

    @Test
    void springJpaLombokFixtureDetectsAllExpectedFrameworks() {
        Path root = FIXTURES.resolve("maven-spring-jpa-lombok");

        assertThat(ProjectDetector.detectFrameworks(root, root, BuildTool.MAVEN)).containsExactlyInAnyOrder(
                Framework.SPRING_BOOT, Framework.SPRING_DATA_JPA, Framework.HIBERNATE, Framework.JAKARTA_PERSISTENCE,
                Framework.JAKARTA_EE, Framework.LOMBOK, Framework.MAPSTRUCT, Framework.JUNIT5, Framework.MOCKITO, Framework.TESTCONTAINERS);
    }

    @Test
    void plainFixtureDetectsNoFrameworks() {
        Path root = FIXTURES.resolve("maven-simple");

        assertThat(ProjectDetector.detectFrameworks(root, root, BuildTool.MAVEN)).containsExactly(Framework.JUNIT5);
    }

    @Test
    void legacyFixtureDetectsJavaxAndJUnit4NotJakartaOrJUnit5() {
        Path root = FIXTURES.resolve("legacy-dirty");

        assertThat(ProjectDetector.detectFrameworks(root, root, BuildTool.MAVEN))
                .containsExactlyInAnyOrder(Framework.JAVAX_PERSISTENCE, Framework.JUNIT4);
    }

    @Test
    void multiModuleChildInheritsFrameworkDeclaredOnlyOnTheModuleItself() {
        Path reactor = FIXTURES.resolve("maven-multimodule");
        Path moduleB = reactor.resolve("module-b");

        // module-b declares junit-jupiter itself; module-a declares nothing.
        assertThat(ProjectDetector.detectFrameworks(moduleB, reactor, BuildTool.MAVEN)).contains(Framework.JUNIT5);
        assertThat(ProjectDetector.detectFrameworks(reactor.resolve("module-a"), reactor, BuildTool.MAVEN))
                .doesNotContain(Framework.JUNIT5);
    }

    @Test
    void gradleFrameworksAreDetectedByCoarseTextSearch(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("build.gradle.kts"), """
                dependencies {
                    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
                    compileOnly("org.projectlombok:lombok:1.18.34")
                    testImplementation("org.junit.jupiter:junit-jupiter")
                }
                """);

        assertThat(ProjectDetector.detectFrameworks(dir, dir, BuildTool.GRADLE))
                .containsExactlyInAnyOrder(Framework.SPRING_BOOT, Framework.SPRING_DATA_JPA, Framework.LOMBOK, Framework.JUNIT5);
    }

    // --- convenience detect() ------------------------------------------------------

    @Test
    void detectReturnsAFullyPopulatedModuleInfo() {
        Path file = FIXTURES.resolve("maven-spring-jpa-lombok/src/main/java/com/acme/entity/User.java");

        ModuleInfo info = ProjectDetector.detect(file, Optional.empty()).orElseThrow();

        assertThat(info.moduleRoot()).isEqualTo(FIXTURES.resolve("maven-spring-jpa-lombok"));
        assertThat(info.reactorRoot()).isEqualTo(info.moduleRoot());
        assertThat(info.buildTool()).isEqualTo(BuildTool.MAVEN);
        assertThat(info.javaRelease()).isEqualTo(21);
        assertThat(info.frameworks()).contains(Framework.LOMBOK, Framework.HIBERNATE);
        assertThat(info.isMainSource(file)).isTrue();
        assertThat(info.isTestSource(file)).isFalse();
    }

    @Test
    void detectIsEmptyOutsideAnyProject(@TempDir Path outside) {
        assertThat(ProjectDetector.detect(outside.resolve("X.java"), Optional.empty())).isEmpty();
    }
}
