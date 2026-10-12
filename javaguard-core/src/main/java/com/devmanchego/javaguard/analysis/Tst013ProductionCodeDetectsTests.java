package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * JG-TST-013 (catalog &sect;1.7): production code that knows it is under test - it imports a test
 * framework, probes for one with {@code Class.forName}, inspects the stack trace for "Test", or reads a
 * "test mode" property. That is the signature of code special-cased to make a test pass. Introduced
 * occurrences only (the baseline filter handles the rest). Production sources only.
 */
final class Tst013ProductionCodeDetectsTests extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-TST-013", "Production code detects tests", Category.TEST_INTEGRITY,
            Severity.CRITICAL);

    private static final List<String> TEST_PACKAGES = List.of("org.junit", "org.mockito", "org.testng", "junit.framework");
    private static final Pattern TEST_MODE = Pattern.compile("(?i)(^|[^a-z0-9])test(ing)?[._-]?mode([^a-z0-9]|$)");
    private static final Pattern TEST_MARKER = Pattern.compile("(?i)junit|surefire|mockito|testng|Test");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        if (ctx.testScope()) {
            return out;
        }
        for (ImportDeclaration imp : ctx.unit().getImports()) {
            String name = imp.getNameAsString();
            if (TEST_PACKAGES.stream().anyMatch(p -> name.equals(p) || name.startsWith(p + "."))) {
                out.add(finding(ctx, imp, "Production code imports the test framework '" + name + "'.",
                        "Production code must not depend on test frameworks; move the logic to tests or remove the "
                                + "special case."));
            }
        }
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (call.getNameAsString().equals("forName") && call.getArguments().size() >= 1
                    && call.getArgument(0) instanceof StringLiteralExpr literal
                    && TEST_PACKAGES.stream().anyMatch(p -> literal.getValue().startsWith(p))) {
                out.add(finding(ctx, call, "Production code probes for the test framework class '" + literal.getValue() + "'.",
                        "Do not make production behavior depend on whether a test framework is present."));
            }
        }
        for (MethodDeclaration method : ctx.unit().findAll(MethodDeclaration.class)) {
            boolean readsStack = method.findAll(MethodCallExpr.class).stream()
                    .anyMatch(c -> c.getNameAsString().equals("getStackTrace"));
            if (!readsStack) {
                continue;
            }
            method.findAll(StringLiteralExpr.class).stream()
                    .filter(s -> TEST_MARKER.matcher(s.getValue()).find())
                    .findFirst()
                    .ifPresent(s -> out.add(finding(ctx, s, "Production code inspects the stack trace for a test marker ('"
                            + s.getValue() + "').", "Do not special-case behavior on the caller being a test.")));
        }
        for (StringLiteralExpr literal : ctx.unit().findAll(StringLiteralExpr.class)) {
            if (TEST_MODE.matcher(literal.getValue()).find()) {
                out.add(finding(ctx, literal, "Production code reads a test-mode switch ('" + literal.getValue() + "').",
                        "Remove the test-mode branch; tests must exercise the real behavior."));
            }
        }
        return out;
    }
}
