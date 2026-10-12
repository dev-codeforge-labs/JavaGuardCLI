package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;

import java.util.Optional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-REL-004 (catalog &sect;1.4): inside a {@code catch}, a new exception is
 * thrown without attaching the original as its cause - the stack trace of the
 * real failure is lost.
 */
final class Rel004CauseDroppedOnRethrow extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-REL-004", "Cause dropped when rethrowing", Category.RELIABILITY, Severity.MAJOR);

    private static final Set<String> ATTACHES_CAUSE = Set.of("initCause", "addSuppressed");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (CatchClause cc : ctx.unit().findAll(CatchClause.class)) {
            String excName = cc.getParameter().getNameAsString();
            for (ThrowStmt t : cc.getBody().findAll(ThrowStmt.class)) {
                if (refersTo(t.getExpression(), excName)) {
                    continue; // "throw e;" (bare rethrow) needs no cause: it IS the original.
                }
                ObjectCreationExpr creation = newExceptionBehind(cc, t.getExpression()).orElse(null);
                if (creation == null) {
                    continue; // not a "new X(...)" thrown directly or via a local built from one in this catch
                }
                boolean includesCause = creation.getArguments().stream().anyMatch(a -> refersTo(a, excName));
                boolean attachedSeparately = cc.getBody().findAll(MethodCallExpr.class).stream()
                        .anyMatch(c -> ATTACHES_CAUSE.contains(c.getNameAsString())
                                && c.getArguments().stream().anyMatch(a -> refersTo(a, excName)));
                if (!includesCause && !attachedSeparately) {
                    out.add(finding(ctx, t, "A new exception is thrown here without attaching '" + excName + "' as its cause.",
                            "Pass the caught exception to the new one's constructor, or call initCause(...) before throwing."));
                }
            }
        }
        return out;
    }

    /**
     * The constructor call behind {@code thrown}: itself, if {@code thrown} is
     * {@code new X(...)} directly, or the initializer of a local variable built
     * that way earlier in the same catch block ({@code X w = new X(...); throw w;}).
     */
    private Optional<ObjectCreationExpr> newExceptionBehind(CatchClause cc, Expression thrown) {
        if (thrown.isObjectCreationExpr()) {
            return Optional.of(thrown.asObjectCreationExpr());
        }
        if (!thrown.isNameExpr()) {
            return Optional.empty();
        }
        String name = thrown.asNameExpr().getNameAsString();
        return cc.getBody().findAll(VariableDeclarator.class).stream()
                .filter(v -> v.getNameAsString().equals(name))
                .findFirst()
                .flatMap(VariableDeclarator::getInitializer)
                .filter(Expression::isObjectCreationExpr)
                .map(Expression::asObjectCreationExpr);
    }

    private boolean refersTo(Expression e, String name) {
        return e.isNameExpr() && e.asNameExpr().getNameAsString().equals(name);
    }
}
