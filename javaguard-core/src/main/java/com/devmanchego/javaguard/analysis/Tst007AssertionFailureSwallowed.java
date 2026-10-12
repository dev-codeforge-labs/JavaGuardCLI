package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.integrity.TestCalls;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-TST-007 (catalog &sect;1.7): a {@code try} whose body asserts and whose {@code catch} catches
 * {@code AssertionError}, {@code Error} or {@code Throwable} (or JUnit's own failure types) without
 * rethrowing. A failing assertion then disappears and the test goes green. Catching a specific exception
 * around {@code fail()} is the classic expected-exception idiom and is not flagged. Test sources only.
 */
final class Tst007AssertionFailureSwallowed extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-TST-007", "Assertion failure swallowed", Category.TEST_INTEGRITY,
            Severity.CRITICAL);

    private static final Set<String> CATCHES_ASSERTION_ERRORS = Set.of("AssertionError", "Error", "Throwable",
            "AssertionFailedError", "ComparisonFailure", "MultipleFailuresError");

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
        for (TryStmt tryStmt : ctx.unit().findAll(TryStmt.class)) {
            boolean asserts = tryStmt.getTryBlock().findAll(MethodCallExpr.class).stream().anyMatch(TestCalls::isAssertion);
            if (!asserts) {
                continue;
            }
            for (CatchClause catchClause : tryStmt.getCatchClauses()) {
                if (catchesAssertionErrors(catchClause) && !rethrowsOrFails(catchClause)) {
                    out.add(finding(ctx, catchClause, "A failing assertion inside the try block is caught by 'catch ("
                            + catchClause.getParameter().getType() + ")' and not rethrown, so the test cannot fail.",
                            "Remove the try/catch, or catch only the specific exception the test expects, or rethrow."));
                }
            }
        }
        return out;
    }

    private static boolean catchesAssertionErrors(CatchClause catchClause) {
        Type type = catchClause.getParameter().getType();
        List<Type> alternatives = type.isUnionType() ? new ArrayList<>(type.asUnionType().getElements()) : List.of(type);
        return alternatives.stream().anyMatch(t -> CATCHES_ASSERTION_ERRORS.contains(AstSupport.simpleName(t.asString())));
    }

    private static boolean rethrowsOrFails(CatchClause catchClause) {
        return !catchClause.getBody().findAll(ThrowStmt.class).isEmpty()
                || catchClause.getBody().findAll(MethodCallExpr.class).stream().anyMatch(TestCalls::isFail);
    }
}
