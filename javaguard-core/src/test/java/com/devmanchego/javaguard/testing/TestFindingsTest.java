package com.devmanchego.javaguard.testing;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.testing.JUnitXmlParser.TestOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 8: failed tests become {@code TEST} findings; related vs "possibly pre-existing" (specification &sect;10.3). */
class TestFindingsTest {

    private static final Predicate<String> PROJECT = cls -> cls.startsWith("com.acme.");
    private static final String TRACE = "org.opentest4j.AssertionFailedError: expected: <84> but was: <42>\n"
            + "\tat org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:629)\n"
            + "\tat com.acme.moduleb.BarTest.doublesFooValue(BarTest.java:9)\n";

    private static TestOutcome failure(String className) {
        return new TestOutcome(className, "doublesFooValue", false, "expected: <84> but was: <42>",
                "org.opentest4j.AssertionFailedError", TRACE);
    }

    @Test
    void aRelatedFailureIsCriticalWithExpectedActualTraceAndTheTestSourceLine(@TempDir Path root) throws Exception {
        Path testRoot = root.resolve("src/test/java");
        Path source = Files.createDirectories(testRoot.resolve("com/acme/moduleb")).resolve("BarTest.java");
        Files.writeString(source, "package com.acme.moduleb; class BarTest {}");

        Finding f = TestFindings.of(failure("com.acme.moduleb.BarTest"), root, List.of(testRoot),
                Set.of("com.acme.moduleb.BarTest"), PROJECT);

        assertThat(f.ruleId()).isEqualTo("TEST");
        assertThat(f.category()).isEqualTo(Category.TEST_FAILURE);
        assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(f.engine()).isEqualTo("surefire");
        assertThat(f.file()).contains(Path.of("src/test/java/com/acme/moduleb/BarTest.java"));
        assertThat(f.line()).isEqualTo(9);
        assertThat(f.message()).isEqualTo("BarTest.doublesFooValue failed: expected: <84> but was: <42>");
        assertThat(f.detail()).containsEntry("test", "com.acme.moduleb.BarTest#doublesFooValue")
                .containsEntry("classification", "related")
                .containsEntry("expected", "84").containsEntry("actual", "42");
        assertThat(f.detail().get("trace")).contains("at com.acme.moduleb.BarTest.doublesFooValue(BarTest.java:9)")
                .contains("1 framework frame omitted");
        assertThat(f.remediation()).contains("Do not weaken, skip or delete the test");
    }

    @Test
    void anUnrelatedFailureIsMajorAndMarkedPossiblyPreExisting(@TempDir Path root) {
        Finding f = TestFindings.of(failure("com.acme.other.SomethingTest"), root, List.of(root),
                Set.of("com.acme.moduleb.BarTest"), PROJECT);

        assertThat(f.severity()).isEqualTo(Severity.MAJOR);
        assertThat(f.detail()).containsEntry("classification", "possibly-pre-existing");
        assertThat(f.remediation()).contains("not related to your change");
        assertThat(f.file()).isEmpty();
    }

    @Test
    void anErrorIsDescribedAsErroredAndUsesItsExceptionTypeWhenThereIsNoMessage(@TempDir Path root) {
        TestOutcome error = new TestOutcome("com.acme.AT", "t", true, "", "java.lang.NullPointerException",
                "java.lang.NullPointerException\n\tat com.acme.A.m(A.java:3)");

        Finding f = TestFindings.of(error, root, List.of(), Set.of("com.acme.AT"), PROJECT);

        assertThat(f.message()).isEqualTo("AT.t errored: java.lang.NullPointerException");
    }

    @Test
    void sameFailureHasTheSameFingerprintWhateverTheLineNumbers(@TempDir Path root) {
        Finding a = TestFindings.of(failure("com.acme.moduleb.BarTest"), root, List.of(), Set.of(), PROJECT);
        TestOutcome moved = new TestOutcome("com.acme.moduleb.BarTest", "doublesFooValue", false,
                "expected: <84> but was: <42>", "org.opentest4j.AssertionFailedError", TRACE.replace(":9)", ":19)"));
        Finding b = TestFindings.of(moved, root, List.of(), Set.of(), PROJECT);

        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
    }

    @Test
    void expectedActualRecognizesJUnit5JUnit4AndAssertJShapes() {
        assertThat(TestFindings.expectedActual("expected: <a> but was: <b>")).hasValueSatisfying(p -> assertThat(p).containsExactly("a", "b"));
        assertThat(TestFindings.expectedActual("expected:<a> but was:<b>")).hasValueSatisfying(p -> assertThat(p).containsExactly("a", "b"));
        assertThat(TestFindings.expectedActual("\nexpected: 84\n but was: 42")).hasValueSatisfying(p -> assertThat(p).containsExactly("84", "42"));
        assertThat(TestFindings.expectedActual("something else entirely")).isEmpty();
    }

    @Test
    void longValuesAreClippedAndNewlinesEscaped(@TempDir Path root) {
        String big = "x".repeat(500);
        TestOutcome o = new TestOutcome("a.T", "t", false, "expected: <" + big + "> but was: <l1\nl2>",
                "org.opentest4j.AssertionFailedError", "e\n\tat a.T.t(T.java:1)");

        Finding f = TestFindings.of(o, root, List.of(), Set.of(), PROJECT);

        assertThat(f.detail().get("expected")).hasSize(200).endsWith("...");
        assertThat(f.detail().get("actual")).isEqualTo("l1\\nl2");
    }
}
