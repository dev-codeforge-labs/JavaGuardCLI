package com.devmanchego.javaguard.integrity;

import com.devmanchego.javaguard.model.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 17: JG-TST-009 on {@code build.gradle} and {@code build.gradle.kts}. */
class GradleBuildFileChecksTest {

    private static final String BASE_KTS = """
            plugins { java }
            tasks.test {
                useJUnitPlatform()
            }
            """;

    private final TestIntegrityEngine engine = new TestIntegrityEngine();

    private List<Finding> check(String file, String before, String after) {
        return engine.checkBuildFile(file, before, after);
    }

    @Test
    void gradleBuildFilesAreRecognized() {
        assertThat(TestIntegrityEngine.isBuildFile("build.gradle")).isTrue();
        assertThat(TestIntegrityEngine.isBuildFile("app/build.gradle.kts")).isTrue();
        assertThat(TestIntegrityEngine.isBuildFile("app/settings.gradle")).isFalse();
    }

    @Test
    void disablingTheTestTaskIsFlaggedInBothDsls() {
        List<Finding> kotlin = check("build.gradle.kts", BASE_KTS, BASE_KTS.replace("useJUnitPlatform()", "useJUnitPlatform()\n    enabled = false"));
        List<Finding> groovy = check("build.gradle", "test { useJUnitPlatform() }\n", "test {\n  useJUnitPlatform()\n  enabled = false\n}\n");

        assertThat(kotlin).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("JG-TST-009");
            assertThat(f.message()).contains("disabled");
            assertThat(f.line()).isEqualTo(4);
        });
        assertThat(groovy).hasSize(1);
    }

    @Test
    void ignoreFailuresOnlyIfFalseAndExcludesAreFlagged() {
        String after = """
                plugins { java }
                tasks.withType<Test> {
                    ignoreFailures = true
                    onlyIf { false }
                    exclude("**/SlowTest*")
                    filter { excludeTestsMatching("*Flaky*") }
                    useJUnitPlatform { excludeTags("integration") }
                }
                """;

        List<Finding> findings = check("build.gradle.kts", "plugins { java }\n", after);

        assertThat(findings).extracting(Finding::message).anyMatch(m -> m.contains("ignored"))
                .anyMatch(m -> m.contains("onlyIf"))
                .anyMatch(m -> m.contains("**/SlowTest*"))
                .anyMatch(m -> m.contains("*Flaky*"))
                .anyMatch(m -> m.contains("integration"));
        assertThat(findings).hasSize(5);
    }

    @Test
    void groovyStyleStringsAndNamedTasksAreUnderstood() {
        String after = "tasks.named('test') {\n    exclude '**/BrokenTest.class'\n    setIgnoreFailures(true)\n}\n";

        assertThat(check("build.gradle", "", after)).hasSize(2);
    }

    @Test
    void oneLineSpellingsAreFlagged() {
        assertThat(check("build.gradle", "", "tasks.test.enabled = false\n")).hasSize(1);
        assertThat(check("build.gradle.kts", "", "tasks.test.get().enabled = false\n")).isEmpty(); // not recognized: documented limit
    }

    @Test
    void whatWasAlreadyThereIsNotNewAndCommentsAreIgnored() {
        String weak = "tasks.test {\n    exclude(\"**/Old*\")\n}\n";

        assertThat(check("build.gradle.kts", weak, weak + "// enabled = false\n/* ignoreFailures = true */\n")).isEmpty();
        assertThat(check("build.gradle.kts", weak, weak.replace("}", "    exclude(\"**/New*\")\n}"))).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("**/New*"));
    }

    @Test
    void settingsOutsideTestBlocksAreNotTestWeakening() {
        String after = "tasks.register(\"docs\") {\n    enabled = false\n    exclude(\"**/x\")\n}\nsourceSets { test { java.srcDir(\"src/it\") } }\n";

        assertThat(check("build.gradle.kts", "", after)).isEmpty();
    }

    @Test
    void anUnbalancedFileDoesNotBlowUp() {
        assertThat(check("build.gradle", "", "test {\n  enabled = false\n")).hasSize(1);
        assertThat(check("build.gradle", "", "")).isEmpty();
    }
}
