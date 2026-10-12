package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayList;
import java.util.List;

/**
 * JG-PERF-008 (catalog &sect;1.3): a {@code while} loop whose entire body is a
 * call to {@code Thread.sleep(...)} - polling a condition instead of waiting
 * on it.
 */
final class Perf008BusyWait extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-PERF-008", "Busy wait", Category.PERFORMANCE, Severity.MAJOR);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (WhileStmt w : ctx.unit().findAll(WhileStmt.class)) {
            if (bodyIsOnlyThreadSleep(w.getBody())) {
                out.add(finding(ctx, w, "This loop polls a condition by sleeping instead of waiting to be signaled.",
                        "Use wait()/notifyAll(), a java.util.concurrent primitive (CountDownLatch, condition variable), "
                                + "or a blocking call, instead of a sleep-and-check loop."));
            }
        }
        return out;
    }

    private boolean bodyIsOnlyThreadSleep(Statement body) {
        Statement single = body.isBlockStmt() ? onlyStatement(body.asBlockStmt()) : body;
        if (single == null || !single.isExpressionStmt()) {
            return false;
        }
        Expression e = single.asExpressionStmt().getExpression();
        return e.isMethodCallExpr() && e.asMethodCallExpr().getNameAsString().equals("sleep")
                && e.asMethodCallExpr().getScope()
                        .filter(s -> s.isNameExpr() && s.asNameExpr().getNameAsString().equals("Thread")).isPresent();
    }

    private Statement onlyStatement(BlockStmt block) {
        return block.getStatements().size() == 1 ? block.getStatements().get(0) : null;
    }
}
