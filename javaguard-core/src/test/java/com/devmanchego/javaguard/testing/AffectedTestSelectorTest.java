package com.devmanchego.javaguard.testing;

import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.config.JavaGuardConfig.TestScope;
import com.devmanchego.javaguard.project.ReactorModules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 8: affected-test selection (specification &sect;10.3 step 3) on a copy of the multi-module fixture. */
class AffectedTestSelectorTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/maven-multimodule").toAbsolutePath().normalize();

    @TempDir
    Path root;

    private Path moduleA;
    private Path moduleB;
    private Path foo;

    @BeforeEach
    void project() throws IOException {
        try (Stream<Path> walk = Files.walk(FIXTURE)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path target = root.resolve(FIXTURE.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else if (!source.toString().replace('\\', '/').contains("/target/")) {
                    Files.copy(source, target);
                }
            }
        }
        moduleA = root.resolve("module-a");
        moduleB = root.resolve("module-b");
        foo = moduleA.resolve("src/main/java/com/acme/modulea/Foo.java");
        write(moduleA, "src/test/java/com/acme/modulea/FooTest.java", "package com.acme.modulea;\nclass FooTest {}\n");
        write(moduleA, "src/test/java/com/acme/modulea/FooIT.java", "package com.acme.modulea;\nclass FooIT {}\n");
        write(moduleB, "src/test/java/com/acme/moduleb/UsesFooTest.java",
                "package com.acme.moduleb;\nimport com.acme.modulea.Foo;\nclass UsesFooTest { Foo f; }\n");
        write(moduleB, "src/test/java/com/acme/moduleb/MentionsFooInACommentTest.java",
                "package com.acme.moduleb;\n// Foo is somebody else's class\nclass MentionsFooInACommentTest {}\n");
        write(moduleB, "src/test/java/com/acme/moduleb/WildcardTest.java",
                "package com.acme.moduleb;\nimport com.acme.modulea.*;\nclass WildcardTest { Foo f; }\n");
    }

    private void write(Path module, String relative, String content) throws IOException {
        Path file = module.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private AffectedTestSelector selector(TestScope scope, int max, boolean integration) {
        return new AffectedTestSelector(ReactorModules.load(root),
                new JavaGuardConfig.Tests(scope, max, integration, JavaGuardConfig.Policy.ADVISE));
    }

    @Test
    void picksTestsNamedAfterTheClassAndTestsThatImportIt() {
        TestSelection selection = selector(TestScope.AFFECTED, 50, false).select(List.of(foo));

        assertThat(selection.classes()).containsExactlyInAnyOrder("com.acme.modulea.FooTest",
                "com.acme.moduleb.UsesFooTest", "com.acme.moduleb.WildcardTest");
        assertThat(selection.wholeModules()).isFalse();
        assertThat(selection.moduleRoots()).containsExactlyInAnyOrder(moduleA, moduleB);
        assertThat(selection.relatedClasses()).containsExactlyInAnyOrderElementsOf(selection.classes());
    }

    @Test
    void aMereMentionOfTheSimpleNameWithoutAnImportDoesNotSelectATest() {
        TestSelection selection = selector(TestScope.AFFECTED, 50, false).select(List.of(foo));

        assertThat(selection.classes()).doesNotContain("com.acme.moduleb.MentionsFooInACommentTest",
                "com.acme.moduleb.BarTest");
    }

    @Test
    void integrationTestsAreExcludedUnlessRequested() {
        assertThat(selector(TestScope.AFFECTED, 50, false).select(List.of(foo)).classes())
                .doesNotContain("com.acme.modulea.FooIT");
        assertThat(selector(TestScope.AFFECTED, 50, true).select(List.of(foo)).classes())
                .contains("com.acme.modulea.FooIT");
    }

    @Test
    void aChangedTestFileIsSelectedByItself() {
        Path barTest = moduleB.resolve("src/test/java/com/acme/moduleb/BarTest.java");

        TestSelection selection = selector(TestScope.AFFECTED, 50, false).select(List.of(barTest));

        assertThat(selection.classes()).containsExactly("com.acme.moduleb.BarTest");
        assertThat(selection.moduleRoots()).containsExactly(moduleB);
    }

    @Test
    void aChangeInTheDownstreamModuleDoesNotSelectUpstreamTests() {
        Path bar = moduleB.resolve("src/main/java/com/acme/moduleb/Bar.java");

        TestSelection selection = selector(TestScope.AFFECTED, 50, false).select(List.of(bar));

        assertThat(selection.classes()).containsExactly("com.acme.moduleb.BarTest");
    }

    @Test
    void aboveMaxClassesItFallsBackToTheWholeAffectedModules() {
        TestSelection selection = selector(TestScope.AFFECTED, 2, false).select(List.of(foo));

        assertThat(selection.fellBackToModule()).isTrue();
        assertThat(selection.wholeModules()).isTrue();
        assertThat(selection.scope()).isEqualTo(TestScope.MODULE);
        assertThat(selection.moduleRoots()).containsExactly(moduleA);
        assertThat(selection.classes()).isEmpty();
        assertThat(selection.relatedClasses()).as("kept for classification").hasSize(3);
        assertThat(selection.notes()).anyMatch(n -> n.contains("tests.maxClasses"));
    }

    @Test
    void moduleScopeRunsWholeModulesAndAllScopeRunsTheWholeReactor() {
        TestSelection module = selector(TestScope.MODULE, 50, false).select(List.of(foo));
        assertThat(module.wholeModules()).isTrue();
        assertThat(module.moduleRoots()).containsExactly(moduleA);

        TestSelection all = selector(TestScope.ALL, 50, false).select(List.of(foo));
        assertThat(all.wholeModules()).isTrue();
        assertThat(all.moduleRoots()).contains(moduleA, moduleB);
    }

    @Test
    void noneScopeAndNonJavaChangesSelectNothing() {
        assertThat(selector(TestScope.NONE, 50, false).select(List.of(foo)).isEmpty()).isTrue();
        assertThat(selector(TestScope.AFFECTED, 50, false).select(List.of(root.resolve("README.md"))).isEmpty()).isTrue();
        assertThat(selector(TestScope.AFFECTED, 50, false).select(List.of()).isEmpty()).isTrue();
    }

    @Test
    void aChangeWithNoRelatedTestsSelectsNothing() throws IOException {
        Path lonely = write2(moduleA, "src/main/java/com/acme/modulea/Lonely.java", "package com.acme.modulea;\npublic class Lonely {}\n");

        assertThat(selector(TestScope.AFFECTED, 50, false).select(List.of(lonely)).isEmpty()).isTrue();
    }

    @Test
    void reactorDependentsAreFoundTransitivelyByArtifactId() {
        ReactorModules reactor = ReactorModules.load(root);

        assertThat(reactor.withDependents(java.util.Set.of(moduleA))).contains(moduleA, moduleB);
        assertThat(reactor.withDependents(java.util.Set.of(moduleB))).containsExactly(moduleB);
        assertThat(reactor.selector(moduleB)).isEqualTo("module-b");
        assertThat(reactor.selector(root)).isEqualTo(".");
    }

    private Path write2(Path module, String relative, String content) throws IOException {
        write(module, relative, content);
        return module.resolve(relative);
    }
}
