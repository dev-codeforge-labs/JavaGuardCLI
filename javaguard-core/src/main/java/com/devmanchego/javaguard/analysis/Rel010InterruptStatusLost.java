package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;

import java.util.ArrayList;
import java.util.List;

/**
 * JG-REL-010 (catalog &sect;1.4): {@code catch (InterruptedException e)} that
 * neither rethrows nor restores the interrupt flag - a thread's cancellation
 * signal is silently lost.
 */
final class Rel010InterruptStatusLost extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-REL-010", "Interrupt status lost", Category.RELIABILITY, Severity.MAJOR);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (CatchClause cc : ctx.unit().findAll(CatchClause.class)) {
            if (!"InterruptedException".equals(AstSupport.simpleName(cc.getParameter().getTypeAsString()))) {
                continue;
            }
            boolean rethrows = !cc.getBody().findAll(com.github.javaparser.ast.stmt.ThrowStmt.class).isEmpty();
            boolean restoresFlag = cc.getBody().findAll(MethodCallExpr.class).stream()
                    .anyMatch(c -> c.getNameAsString().equals("interrupt"));
            if (!rethrows && !restoresFlag) {
                out.add(finding(ctx, cc, "InterruptedException is caught but the interrupt status is not restored.",
                        "Call Thread.currentThread().interrupt() before returning, or rethrow."));
            }
        }
        return out;
    }
}
