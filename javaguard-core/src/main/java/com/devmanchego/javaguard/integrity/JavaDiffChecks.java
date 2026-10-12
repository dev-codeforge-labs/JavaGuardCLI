package com.devmanchego.javaguard.integrity;

import com.devmanchego.javaguard.integrity.TestMethods.Method;
import com.devmanchego.javaguard.integrity.TestMethods.Pair;
import com.devmanchego.javaguard.integrity.TestMethods.Pairing;
import com.devmanchego.javaguard.model.Finding;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.TryStmt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The diff-based rules over a test source file: JG-TST-001, 002, 003, 004, 006, 010, 011 and 012
 * (catalog &sect;1.7). Each compares a baseline test method with the one it became (see {@link TestMethods}).
 * One method gets at most one of "emptied" / "assertions removed" / "exception expectation removed": the
 * most specific explanation wins, so a gutted test is a single finding.
 */
final class JavaDiffChecks {

    private static final Pattern DISABLING = Pattern.compile(
            "Disabled\\w*|Ignore|EnabledIf\\w*|EnabledOn\\w*|EnabledFor\\w*|Test\\(enabled=false\\)");
    private static final Set<String> JUNIT_EQUALITY = Set.of("assertEquals", "assertNotEquals", "assertSame", "assertNotSame",
            "assertArrayEquals", "assertIterableEquals");
    private static final Set<String> ASSERTJ_TERMINALS = Set.of("isEqualTo", "isNotEqualTo", "isSameAs", "isNotSameAs",
            "isEqualByComparingTo", "isEqualToIgnoringCase", "hasSize", "containsExactly", "containsExactlyInAnyOrder",
            "containsOnly", "contains", "startsWith", "endsWith", "hasMessage", "hasMessageContaining");
    private static final double TIMEOUT_FACTOR = 10.0;

    private final String relativePath;
    private final List<String> currentLines;
    private final FindingFactory factory;

    JavaDiffChecks(String relativePath, String currentText, FindingFactory factory) {
        this.relativePath = relativePath;
        this.currentLines = currentText.lines().toList();
        this.factory = factory;
    }

    /** Builds a finding of a given rule at a line of the current file. */
    interface FindingFactory {
        Finding create(String ruleId, String relativePath, int line, String snippet, String message, String remediation);
    }

    List<Finding> check(CompilationUnit baseline, CompilationUnit current) {
        List<Method> baseMethods = TestMethods.index(baseline);
        List<Method> curMethods = TestMethods.index(current);
        Pairing pairing = TestMethods.pair(baseMethods, curMethods);
        Map<String, List<MethodDeclaration>> baseHelpers = TestMethods.byName(baseMethods);
        Map<String, List<MethodDeclaration>> curHelpers = TestMethods.byName(curMethods);

        List<Finding> out = new ArrayList<>();
        removedTests(pairing, out);
        disabledTests(baseline, current, pairing, out);
        for (Pair pair : pairing.pairs()) {
            if (!pair.current().test()) {
                continue;
            }
            Method before = pair.baseline();
            Method after = pair.current();
            boolean emptied = before.statements() > 0 && after.statements() == 0;
            if (emptied) {
                out.add(at("JG-TST-006", after, "The body of test " + after.display() + " was emptied (it had "
                        + before.statements() + " statement(s)).", "Restore the test body; fix the production code instead."));
            } else {
                int assertionsBefore = TestCalls.assertionCount(before.declaration(), baseHelpers);
                int assertionsAfter = TestCalls.assertionCount(after.declaration(), curHelpers);
                if (assertionsAfter < assertionsBefore) {
                    out.add(at("JG-TST-003", after, "Test " + after.display() + " now has " + assertionsAfter
                            + " assertion(s), it had " + assertionsBefore + ".",
                            "Restore the removed assertions; if one is genuinely wrong, say so to the user instead of deleting it."));
                }
                if (expectations(after) < expectations(before)) {
                    out.add(at("JG-TST-012", after, "Test " + after.display() + " no longer expects an exception it expected before.",
                            "Restore the assertThrows / fail() that states the call must throw."));
                }
            }
            changedExpectedValues(before, after, out);
            if (TestCalls.assumptionCount(before.declaration()) == 0 && TestCalls.assumptionCount(after.declaration()) > 0) {
                out.add(at("JG-TST-010", after, "Test " + after.display() + " now starts with an assumption, so it can be "
                        + "skipped instead of failing.", "Remove the assumption unless the test truly depends on the environment."));
            }
            timeoutIncreases(before, after, out);
        }
        return out;
    }

    // --- 001 --------------------------------------------------------------------------------------

    private void removedTests(Pairing pairing, List<Finding> out) {
        for (Method removed : pairing.removed()) {
            out.add(factory.create("JG-TST-001", relativePath, 1, "",
                    "Test " + removed.display() + " was removed.",
                    "Restore the test; if it is obsolete, say so to the user instead of deleting it."));
        }
        for (Pair pair : pairing.pairs()) {
            if (!pair.current().test()) {
                out.add(at("JG-TST-001", pair.current(), "Method " + pair.current().display()
                        + " is no longer a test: its test annotation was removed.", "Restore the @Test annotation."));
            }
        }
    }

    // --- 002 --------------------------------------------------------------------------------------

    private void disabledTests(CompilationUnit baseline, CompilationUnit current, Pairing pairing, List<Finding> out) {
        Map<String, Set<String>> baseTypes = typeAnnotations(baseline);
        typeAnnotations(current).forEach((typePath, annotations) -> {
            Set<String> before = baseTypes.getOrDefault(typePath, Set.of());
            for (String annotation : annotations) {
                if (DISABLING.matcher(annotation).matches() && !before.contains(annotation)) {
                    int line = typeLine(current, typePath);
                    out.add(factory.create("JG-TST-002", relativePath, line, snippet(line),
                            "Test class " + typePath + " is now disabled by @" + annotation + ".",
                            "Do not disable tests; fix the code under test, or tell the user the test is wrong."));
                }
            }
        });
        Map<Method, Method> baselineOf = new HashMap<>();
        pairing.pairs().forEach(p -> baselineOf.put(p.current(), p.baseline()));
        List<Method> currentTests = new ArrayList<>(pairing.added());
        pairing.pairs().stream().map(Pair::current).filter(Method::test).forEach(currentTests::add);
        for (Method method : currentTests) {
            Set<String> before = baselineOf.containsKey(method) ? baselineOf.get(method).annotations() : Set.of();
            for (String annotation : method.annotations()) {
                if (DISABLING.matcher(annotation).matches() && !before.contains(annotation)) {
                    out.add(at("JG-TST-002", method, "Test " + method.display() + " is now disabled by @" + annotation + ".",
                            "Do not disable tests; fix the code under test, or tell the user the test is wrong."));
                }
            }
        }
    }

    private Map<String, Set<String>> typeAnnotations(CompilationUnit unit) {
        Map<String, Set<String>> byType = new LinkedHashMap<>();
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            byType.put(TestMethods.typePath(type).isEmpty() ? type.getNameAsString()
                    : TestMethods.typePath(type) + "." + type.getNameAsString(), TestMethods.annotationNames(type));
        }
        return byType;
    }

    private int typeLine(CompilationUnit unit, String typePath) {
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            String path = TestMethods.typePath(type).isEmpty() ? type.getNameAsString()
                    : TestMethods.typePath(type) + "." + type.getNameAsString();
            if (path.equals(typePath)) {
                return type.getBegin().map(p -> p.line).orElse(1);
            }
        }
        return 1;
    }

    // --- 004 --------------------------------------------------------------------------------------

    /** One recognized assertion: what is asserted about which expression. */
    private record Shape(String kind, String actual, String expected, MethodCallExpr node) {
        String group() {
            return kind + "|" + actual;
        }
    }

    private void changedExpectedValues(Method before, Method after, List<Finding> out) {
        Map<String, List<Shape>> baseGroups = group(shapes(before.declaration()));
        Map<String, List<Shape>> curGroups = group(shapes(after.declaration()));
        for (Map.Entry<String, List<Shape>> entry : curGroups.entrySet()) {
            List<Shape> baseShapes = baseGroups.get(entry.getKey());
            if (baseShapes == null) {
                continue;
            }
            List<String> baseOnly = new ArrayList<>(baseShapes.stream().map(Shape::expected).toList());
            List<Shape> curOnly = new ArrayList<>();
            for (Shape shape : entry.getValue()) {
                if (!baseOnly.remove(shape.expected())) {
                    curOnly.add(shape);
                }
            }
            if (baseOnly.isEmpty()) {
                continue; // Only assertions were added on the same value; nothing was replaced.
            }
            for (int i = 0; i < curOnly.size(); i++) {
                Shape changed = curOnly.get(i);
                String was = i < baseOnly.size() ? baseOnly.get(i) : baseOnly.get(0);
                int line = changed.node().getBegin().map(p -> p.line).orElse(after.line());
                out.add(factory.create("JG-TST-004", relativePath, line, snippet(line),
                        "In " + after.display() + ", the expected value of '" + changed.actual() + "' changed from " + was
                                + " to " + changed.expected() + ".",
                        "Keep the original expectation unless the specified behavior really changed; if it did, tell the "
                                + "user why in your reply."));
            }
        }
    }

    private static Map<String, List<Shape>> group(List<Shape> shapes) {
        Map<String, List<Shape>> groups = new LinkedHashMap<>();
        shapes.forEach(s -> groups.computeIfAbsent(s.group(), k -> new ArrayList<>()).add(s));
        return groups;
    }

    private static List<Shape> shapes(MethodDeclaration method) {
        List<Shape> shapes = new ArrayList<>();
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            List<Expression> args = new ArrayList<>(call.getArguments());
            if (JUNIT_EQUALITY.contains(name) && args.size() >= 2) {
                if (args.size() >= 3 && args.get(0) instanceof StringLiteralExpr) {
                    args.remove(0); // JUnit 4 puts the failure message first.
                }
                if (args.size() >= 2) {
                    shapes.add(new Shape(name, args.get(1).toString(), args.get(0).toString(), call));
                }
            } else if (ASSERTJ_TERMINALS.contains(name)) {
                Expression actual = assertThatArgument(call);
                if (actual != null) {
                    String expected = args.stream().map(Expression::toString).reduce((a, b) -> a + "," + b).orElse("");
                    shapes.add(new Shape(name, actual.toString(), expected, call));
                }
            } else if (name.equals("assertThat") && (args.size() == 2 || args.size() == 3)) {
                Expression actual = args.get(args.size() - 2);
                shapes.add(new Shape("assertThat(matcher)", actual.toString(), args.get(args.size() - 1).toString(), call));
            }
        }
        return shapes;
    }

    /** The {@code x} of {@code assertThat(x).foo().isEqualTo(...)}, or null when the chain is not an AssertJ one. */
    private static Expression assertThatArgument(MethodCallExpr terminal) {
        Expression scope = terminal.getScope().orElse(null);
        while (scope instanceof MethodCallExpr call) {
            if (call.getNameAsString().equals("assertThat") && call.getArguments().size() == 1) {
                return call.getArgument(0);
            }
            scope = call.getScope().orElse(null);
        }
        return null;
    }

    // --- 011 --------------------------------------------------------------------------------------

    private void timeoutIncreases(Method before, Method after, List<Finding> out) {
        OptionalDouble was = timeoutMillis(before.declaration());
        OptionalDouble now = timeoutMillis(after.declaration());
        if (was.isPresent() && now.isPresent() && was.getAsDouble() > 0 && now.getAsDouble() >= was.getAsDouble() * TIMEOUT_FACTOR) {
            out.add(at("JG-TST-011", after, "The timeout of " + after.display() + " grew from " + format(was.getAsDouble())
                    + " to " + format(now.getAsDouble()) + ".", "Make the code fast enough instead of loosening the timeout."));
        }
    }

    /** Largest timeout declared by the method, in milliseconds: {@code @Timeout}, JUnit 4's {@code timeout=}, {@code assertTimeout}. */
    private static OptionalDouble timeoutMillis(MethodDeclaration method) {
        double max = -1;
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = TestMethods.simple(annotation.getNameAsString());
            if (name.equals("Timeout")) {
                double value = -1;
                double unitMillis = 1000;
                if (annotation instanceof SingleMemberAnnotationExpr single) {
                    value = number(single.getMemberValue());
                } else if (annotation instanceof NormalAnnotationExpr normal) {
                    for (MemberValuePair pair : normal.getPairs()) {
                        if (pair.getNameAsString().equals("value")) {
                            value = number(pair.getValue());
                        } else if (pair.getNameAsString().equals("unit")) {
                            unitMillis = unitMillis(pair.getValue().toString());
                        }
                    }
                }
                if (value >= 0) {
                    max = Math.max(max, value * unitMillis);
                }
            } else if (name.equals("Test") && annotation instanceof NormalAnnotationExpr normal) {
                for (MemberValuePair pair : normal.getPairs()) {
                    if (pair.getNameAsString().equals("timeout")) {
                        max = Math.max(max, number(pair.getValue()));
                    }
                }
            }
        }
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if ((call.getNameAsString().equals("assertTimeout") || call.getNameAsString().equals("assertTimeoutPreemptively"))
                    && !call.getArguments().isEmpty() && call.getArgument(0) instanceof MethodCallExpr duration) {
                double value = duration.getArguments().isEmpty() ? -1 : number(duration.getArgument(0));
                double factor = switch (duration.getNameAsString()) {
                    case "ofMillis" -> 1;
                    case "ofSeconds" -> 1000;
                    case "ofMinutes" -> 60_000;
                    case "ofHours" -> 3_600_000;
                    default -> -1;
                };
                if (value >= 0 && factor > 0) {
                    max = Math.max(max, value * factor);
                }
            }
        }
        return max < 0 ? OptionalDouble.empty() : OptionalDouble.of(max);
    }

    private static double number(Expression e) {
        if (e instanceof IntegerLiteralExpr i) {
            return i.asNumber().doubleValue();
        }
        if (e instanceof LongLiteralExpr l) {
            return l.asNumber().doubleValue();
        }
        return -1;
    }

    private static double unitMillis(String unit) {
        String name = unit.substring(unit.lastIndexOf('.') + 1);
        return switch (name) {
            case "MILLISECONDS" -> 1;
            case "MINUTES" -> 60_000;
            case "HOURS" -> 3_600_000;
            case "MICROSECONDS", "NANOSECONDS" -> 0.001;
            default -> 1000;
        };
    }

    private static String format(double millis) {
        return millis >= 1000 ? (long) (millis / 1000) + " s" : (long) millis + " ms";
    }

    // --- 012 --------------------------------------------------------------------------------------

    /** Statements that say "this call must throw": assertThrows & co., {@code @Test(expected=...)}, {@code fail()} inside a try. */
    private static int expectations(Method method) {
        int count = TestCalls.exceptionExpectationCount(method.declaration());
        if (method.annotations().contains("Test(expected)")) {
            count++;
        }
        for (TryStmt tryStmt : method.declaration().findAll(TryStmt.class)) {
            count += (int) tryStmt.getTryBlock().findAll(MethodCallExpr.class).stream().filter(TestCalls::isFail).count();
        }
        return count;
    }

    // --- helpers ----------------------------------------------------------------------------------

    private Finding at(String ruleId, Method method, String message, String remediation) {
        return factory.create(ruleId, relativePath, method.line(), snippet(method.line()), message, remediation);
    }

    private String snippet(int line) {
        return line >= 1 && line <= currentLines.size() ? currentLines.get(line - 1).strip() : "";
    }
}
