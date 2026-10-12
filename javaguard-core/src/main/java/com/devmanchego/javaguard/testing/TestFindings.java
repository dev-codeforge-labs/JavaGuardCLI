package com.devmanchego.javaguard.testing;

import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.testing.JUnitXmlParser.TestOutcome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns failed test cases into {@code TEST} findings (specification &sect;10.3 step 4). A failure in a
 * test related to the change is CRITICAL (blocking at END_OF_TURN); one in an unrelated test is MAJOR
 * and marked {@code classification=possibly-pre-existing} (advisory, unless
 * {@code tests.unrelatedFailures: block}).
 */
public final class TestFindings {

    public static final String RULE_ID = "TEST";
    public static final String RELATED = "related";
    public static final String POSSIBLY_PRE_EXISTING = "possibly-pre-existing";

    private static final int MAX_VALUE_CHARS = 200;
    private static final int MAX_MESSAGE_CHARS = 300;
    // JUnit 5 / opentest4j: "expected: <84> but was: <42>"; JUnit 4: "expected:<84> but was:<42>".
    private static final Pattern ANGLE = Pattern.compile("expected:\\s*<(.*?)>\\s*but was:\\s*<(.*?)>", Pattern.DOTALL);
    // AssertJ: "expected: 84\n but was: 42".
    private static final Pattern LINES = Pattern.compile("expected:\\s*(.+?)\\R\\s*but was:\\s*(.+)");
    private static final Pattern FRAME_LINE = Pattern.compile("\\((\\w+)\\.java:(\\d+)\\)");

    private TestFindings() {
    }

    /**
     * @param projectRoot     findings carry project-relative file paths
     * @param testSourceRoots where to look for the source of a failing test class
     * @param relatedClasses  test classes related to the change
     * @param isProjectClass  see {@link StackTraceTrimmer#projectClasses}
     */
    public static Finding of(TestOutcome outcome, Path projectRoot, Collection<Path> testSourceRoots,
                             Set<String> relatedClasses, Predicate<String> isProjectClass) {
        boolean related = relatedClasses.contains(outcome.className());
        String exception = outcome.exceptionType().isEmpty() ? "Error" : outcome.exceptionType();
        String summary = summarize(outcome);
        String trace = StackTraceTrimmer.trim(outcome.stackTrace(), isProjectClass, outcome.className(),
                outcome.methodName());

        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("test", outcome.id());
        detail.put("classification", related ? RELATED : POSSIBLY_PRE_EXISTING);
        detail.put("exception", exception);
        expectedActual(outcome.message()).ifPresent(pair -> {
            detail.put("expected", clip(pair[0], MAX_VALUE_CHARS));
            detail.put("actual", clip(pair[1], MAX_VALUE_CHARS));
        });
        if (!trace.isBlank()) {
            detail.put("trace", trace);
        }

        Optional<Path> source = sourceFile(outcome.className(), projectRoot, testSourceRoots);
        int line = source.map(s -> lineInTest(outcome)).orElse(0);
        String message = simpleName(outcome.className()) + "." + outcome.methodName() + " "
                + (outcome.error() ? "errored" : "failed") + ": " + summary;
        String remediation = related
                ? "Fix the production code this test exercises. Do not weaken, skip or delete the test."
                : "This test is not related to your change and may already have been failing; fix it only if "
                        + "your change could have caused it.";
        return new Finding(RULE_ID, "Test failure", Category.TEST_FAILURE, related ? Severity.CRITICAL : Severity.MAJOR,
                "surefire", source.map(projectRoot::relativize), line, 0, line, message, remediation, Optional.empty(),
                detail, Fingerprints.ofTest(outcome.id(), exception, summary));
    }

    /** First line of the message (or the exception type when there is none), clipped. */
    static String summarize(TestOutcome outcome) {
        String message = outcome.message().strip();
        String firstLine = message.lines().findFirst().orElse("").strip();
        if (firstLine.isEmpty()) {
            return outcome.exceptionType().isEmpty() ? "no message" : outcome.exceptionType();
        }
        return clip(firstLine, MAX_MESSAGE_CHARS);
    }

    /** {@code [expected, actual]} when the message has one of the well-known shapes. */
    static Optional<String[]> expectedActual(String message) {
        Matcher m = ANGLE.matcher(message);
        if (m.find()) {
            return Optional.of(new String[] {m.group(1), m.group(2)});
        }
        m = LINES.matcher(message);
        if (m.find()) {
            return Optional.of(new String[] {m.group(1).strip(), m.group(2).strip()});
        }
        return Optional.empty();
    }

    private static int lineInTest(TestOutcome outcome) {
        String testFile = simpleName(outcome.className()).replaceAll("\\$.*", "");
        for (String traceLine : outcome.stackTrace().lines().toList()) {
            if (traceLine.contains(outcome.className()) && traceLine.contains("." + outcome.methodName() + "(")) {
                Matcher m = FRAME_LINE.matcher(traceLine);
                if (m.find() && m.group(1).equals(testFile)) {
                    return Integer.parseInt(m.group(2));
                }
            }
        }
        return 0;
    }

    private static Optional<Path> sourceFile(String className, Path projectRoot, Collection<Path> testSourceRoots) {
        String outer = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
        String relative = outer.replace('.', '/') + ".java";
        for (Path root : testSourceRoots) {
            Path candidate = root.resolve(relative).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate) && candidate.startsWith(projectRoot)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static String clip(String text, int max) {
        String oneLine = text.replace("\r", "").replace("\n", "\\n");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 3) + "...";
    }
}
