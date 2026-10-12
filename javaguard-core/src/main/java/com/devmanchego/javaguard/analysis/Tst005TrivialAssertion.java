package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;

import java.util.ArrayList;
import java.util.List;

/**
 * JG-TST-005 (catalog &sect;1.7): an assertion that cannot fail - {@code assertTrue(true)},
 * {@code assertFalse(false)}, {@code assertEquals(x, x)}, {@code assertNotNull("literal")} or
 * {@code assertNotNull(new Foo())}, {@code assertThat(true).isTrue()}. Such a line makes a test pass
 * while checking nothing. Test sources only.
 */
final class Tst005TrivialAssertion extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-TST-005", "Trivial assertion", Category.TEST_INTEGRITY, Severity.CRITICAL);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public boolean mainOnly() {
        return false; // Catalog scope: test.
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        if (!ctx.testScope()) {
            return out;
        }
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            String reason = trivialReason(call);
            if (reason != null) {
                out.add(finding(ctx, call, "Trivial assertion: " + reason + ", so it can never fail.",
                        "Assert something about the code under test, or delete the assertion."));
            }
        }
        return out;
    }

    private static String trivialReason(MethodCallExpr call) {
        String name = call.getNameAsString();
        List<Expression> args = valueArguments(call);
        switch (name) {
            case "assertTrue":
                return args.size() == 1 && isBoolean(args.get(0), true) ? "assertTrue(true)" : null;
            case "assertFalse":
                return args.size() == 1 && isBoolean(args.get(0), false) ? "assertFalse(false)" : null;
            case "assertNull":
                return args.size() == 1 && args.get(0) instanceof NullLiteralExpr ? "assertNull(null)" : null;
            case "assertNotNull":
                return args.size() == 1 && (args.get(0) instanceof StringLiteralExpr || args.get(0).isLiteralExpr()
                        && !(args.get(0) instanceof NullLiteralExpr) || args.get(0) instanceof ObjectCreationExpr
                        || args.get(0) instanceof ArrayCreationExpr) ? "assertNotNull of a value that is never null" : null;
            case "assertEquals":
            case "assertSame":
                return args.size() == 2 && isSimple(args.get(0)) && args.get(0).equals(args.get(1))
                        ? name + " of '" + args.get(0) + "' with itself" : null;
            case "isTrue":
                return assertedBoolean(call, true) ? "assertThat(true).isTrue()" : null;
            case "isFalse":
                return assertedBoolean(call, false) ? "assertThat(false).isFalse()" : null;
            case "isEqualTo":
                return call.getArguments().size() == 1 && assertThatArgument(call).map(a -> isSimple(a) && a.equals(call.getArgument(0)))
                        .orElse(false) ? "isEqualTo of a value with itself" : null;
            default:
                return null;
        }
    }

    /** Arguments minus a failure-message string literal (JUnit 4 puts it first, JUnit 5 last). */
    private static List<Expression> valueArguments(MethodCallExpr call) {
        List<Expression> args = new ArrayList<>(call.getArguments());
        if (args.size() > 1 && args.get(0) instanceof StringLiteralExpr) {
            args.remove(0);
        } else if (args.size() > 1 && args.get(args.size() - 1) instanceof StringLiteralExpr
                && !(args.get(0) instanceof StringLiteralExpr)) {
            args.remove(args.size() - 1);
        }
        return args;
    }

    private static boolean isBoolean(Expression e, boolean value) {
        return e instanceof BooleanLiteralExpr b && b.getValue() == value;
    }

    private static boolean assertedBoolean(MethodCallExpr terminal, boolean value) {
        return assertThatArgument(terminal).map(a -> isBoolean(a, value)).orElse(false);
    }

    /** The {@code x} of {@code assertThat(x).isFoo(...)}. */
    private static java.util.Optional<Expression> assertThatArgument(MethodCallExpr terminal) {
        return terminal.getScope().filter(Expression::isMethodCallExpr).map(Expression::asMethodCallExpr)
                .filter(m -> m.getNameAsString().equals("assertThat") && m.getArguments().size() == 1)
                .map(m -> m.getArgument(0));
    }

    /** A literal, a name, a field access or {@code this}: no side effects, so equal text means equal value. */
    private static boolean isSimple(Expression e) {
        return e.isLiteralExpr() || e instanceof NameExpr || e instanceof ThisExpr
                || (e instanceof FieldAccessExpr f && isSimple(f.getScope()));
    }
}
