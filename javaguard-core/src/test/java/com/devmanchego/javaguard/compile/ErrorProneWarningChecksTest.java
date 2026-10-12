package com.devmanchego.javaguard.compile;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 24: each Error Prone warning check enabled in this phase fires through the in-process compile, at the documented
 * severity and category, on a seeded source; and stays silent on the safe form of the same code.
 */
class ErrorProneWarningChecksTest {

    @TempDir
    Path projectRoot;

    @TempDir
    Path outputDir;

    static Stream<Arguments> checks() {
        return Stream.of(
                Arguments.of("DefaultLocale", Severity.INFO, Category.RELIABILITY,
                        "class T { String f(double d) { return String.format(\"%.2f\", d); } }",
                        "class T { String f(double d) { return String.format(java.util.Locale.ROOT, \"%.2f\", d); } }"),
                Arguments.of("StringCaseLocaleUsage", Severity.MAJOR, Category.RELIABILITY,
                        "class T { String f(String s) { return s.toLowerCase(); } }",
                        "class T { String f(String s) { return s.toLowerCase(java.util.Locale.ROOT); } }"),
                Arguments.of("IntLongMath", Severity.MAJOR, Category.RELIABILITY,
                        "class T { long f(int a, int b) { return a * b; } }",
                        "class T { long f(int a, int b) { return (long) a * b; } }"),
                Arguments.of("NarrowingCompoundAssignment", Severity.MAJOR, Category.RELIABILITY,
                        "class T { int f(int x, double r) { x *= r; return x; } }",
                        "class T { int f(int x, double r) { x = (int) (x * r); return x; } }"),
                Arguments.of("UnsynchronizedOverridesSynchronized", Severity.MAJOR, Category.CONCURRENCY,
                        "class P { synchronized void m() { } } class T extends P { @Override void m() { System.out.println(); } }",
                        "class P { synchronized void m() { } } class T extends P { @Override synchronized void m() { System.out.println(); } }"),
                Arguments.of("ModifiedButNotUsed", Severity.INFO, Category.AI_DEBRIS,
                        "class T { void f() { java.util.List<String> l = new java.util.ArrayList<>(); l.add(\"x\"); } }",
                        "class T { int f() { java.util.List<String> l = new java.util.ArrayList<>(); l.add(\"x\"); return l.size(); } }"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("checks")
    void firesOnTheSeedAtTheDocumentedSeverityAndNotOnTheSafeForm(String check, Severity severity, Category category,
                                                                 String seeded, String safe) throws IOException {
        List<Finding> bad = compile(seeded);
        List<Finding> good = compile(safe);

        assertThat(bad).filteredOn(f -> f.ruleId().equals("EP:" + check)).as("seeded: %s", bad).isNotEmpty()
                .allSatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(severity);
                    assertThat(f.category()).isEqualTo(category);
                    assertThat(f.engine()).isEqualTo("errorprone");
                });
        assertThat(good).filteredOn(f -> f.ruleId().equals("EP:" + check)).as("safe form: %s", good).isEmpty();
    }


    private List<Finding> compile(String source) throws IOException {
        Path file = projectRoot.resolve("T.java");
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return new InProcessCompiler().compile(new CompileRequest(List.of(file), 21, List.of(), List.of(projectRoot), outputDir,
                projectRoot, true)).findings();
    }
}
