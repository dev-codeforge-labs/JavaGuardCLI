package com.devmanchego.javaguard.testing;

import org.junit.jupiter.api.Test;

import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 8: stack-trace trimming (specification &sect;10.4). */
class StackTraceTrimmerTest {

    private static final Predicate<String> PROJECT = cls -> cls.startsWith("com.acme.");

    private static final String ASSERTION_TRACE = """
            org.opentest4j.AssertionFailedError: expected: <84> but was: <42>
            \tat org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
            \tat org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
            \tat org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:197)
            \tat org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:166)
            \tat org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:629)
            \tat com.acme.moduleb.BarTest.doublesFooValue(BarTest.java:9)
            \tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
            \tat java.base/java.lang.reflect.Method.invoke(Method.java:580)
            \tat org.junit.platform.commons.util.ReflectionUtils.invokeMethod(ReflectionUtils.java:728)
            \tat java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
            """;

    @Test
    void keepsTheExceptionLineAndProjectFramesAndCollapsesTheRest() {
        String trimmed = StackTraceTrimmer.trim(ASSERTION_TRACE, PROJECT, "com.acme.moduleb.BarTest", "doublesFooValue");

        assertThat(trimmed.lines().toList()).containsExactly(
                "org.opentest4j.AssertionFailedError: expected: <84> but was: <42>",
                "  ... 5 framework frames omitted",
                "  at com.acme.moduleb.BarTest.doublesFooValue(BarTest.java:9)",
                "  ... 4 framework frames omitted");
    }

    @Test
    void theTestMethodFrameIsKeptEvenWhenNoPackageIsKnownAsProjectCode() {
        String trimmed = StackTraceTrimmer.trim(ASSERTION_TRACE, cls -> false, "com.acme.moduleb.BarTest", "doublesFooValue");

        assertThat(trimmed).contains("at com.acme.moduleb.BarTest.doublesFooValue(BarTest.java:9)");
    }

    @Test
    void causedByChainsKeepTheirHeaderAndProjectFrames() {
        String trace = """
                java.lang.IllegalStateException: wrapper
                \tat com.acme.Service.run(Service.java:20)
                \tat org.junit.Runner.run(Runner.java:1)
                Caused by: java.io.IOException: disk
                \tat java.base/java.io.FileInputStream.open0(Native Method)
                \tat com.acme.Repo.load(Repo.java:7)
                \t... 12 more
                """;

        String trimmed = StackTraceTrimmer.trim(trace, PROJECT, "com.acme.ServiceTest", "t");

        assertThat(trimmed.lines().toList()).containsExactly(
                "java.lang.IllegalStateException: wrapper",
                "  at com.acme.Service.run(Service.java:20)",
                "  ... 1 framework frame omitted",
                "Caused by: java.io.IOException: disk",
                "  ... 1 framework frame omitted",
                "  at com.acme.Repo.load(Repo.java:7)");
    }

    @Test
    void multiLineExceptionMessagesAreReducedToTheFirstLine() {
        String trace = "org.opentest4j.AssertionFailedError: first line\nsecond line of the message\n\tat com.acme.A.t(A.java:1)\n";

        assertThat(StackTraceTrimmer.trim(trace, PROJECT, "com.acme.A", "t").lines().toList())
                .containsExactly("org.opentest4j.AssertionFailedError: first line", "  at com.acme.A.t(A.java:1)");
    }

    @Test
    void neverKeepsMoreThanFifteenFrames() {
        String frames = IntStream.range(0, 40).mapToObj(i -> "\tat com.acme.Deep.m" + i + "(Deep.java:" + i + ")")
                .collect(Collectors.joining("\n"));

        String trimmed = StackTraceTrimmer.trim("java.lang.StackOverflowError\n" + frames, PROJECT, "x.T", "t");

        assertThat(trimmed.lines().filter(l -> l.startsWith("  at "))).hasSize(StackTraceTrimmer.MAX_FRAMES);
        assertThat(trimmed).endsWith("... 25 framework frames omitted");
    }

    @Test
    void moduleSystemPrefixesOnFramesAreIgnoredWhenClassifying() {
        String trace = "java.lang.RuntimeException: x\n\tat app//com.acme.Foo.bar(Foo.java:3)\n";

        assertThat(StackTraceTrimmer.trim(trace, PROJECT, "x.T", "t")).contains("at app//com.acme.Foo.bar(Foo.java:3)");
    }

    @Test
    void anEmptyTraceStaysEmpty() {
        assertThat(StackTraceTrimmer.trim("", PROJECT, "x.T", "t")).isEmpty();
    }

    @Test
    void projectClassesAreRecognizedFromSourceRootPackages(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root)
            throws Exception {
        java.nio.file.Path pkg = java.nio.file.Files.createDirectories(root.resolve("com/acme/billing"));
        java.nio.file.Files.writeString(pkg.resolve("Invoice.java"), "package com.acme.billing; class Invoice {}");

        Predicate<String> isProject = StackTraceTrimmer.projectClasses(java.util.List.of(root));

        assertThat(isProject.test("com.acme.billing.Invoice")).isTrue();
        assertThat(isProject.test("com.acme.billing.Invoice$Line")).isTrue();
        assertThat(isProject.test("org.junit.Assertions")).isFalse();
        assertThat(isProject.test("com.acme.other.Thing")).isFalse();
    }
}
