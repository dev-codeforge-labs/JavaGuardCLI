package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;

import java.util.ArrayList;
import java.util.List;

/**
 * JG-REL-011 (catalog &sect;1.4): {@code return}/{@code throw}/{@code break}/
 * {@code continue} inside a {@code finally} block silently discards whatever
 * exception was propagating.
 */
final class Rel011ControlFlowInFinally extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-REL-011", "Control flow in finally", Category.RELIABILITY, Severity.CRITICAL);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public boolean mainOnly() {
        return false; // Catalog scope: all.
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (TryStmt t : ctx.unit().findAll(TryStmt.class)) {
            t.getFinallyBlock().ifPresent(fin -> {
                collect(fin.findAll(ReturnStmt.class), fin, out, ctx, "return");
                collect(fin.findAll(ThrowStmt.class), fin, out, ctx, "throw");
                collect(fin.findAll(BreakStmt.class), fin, out, ctx, "break");
                collect(fin.findAll(ContinueStmt.class), fin, out, ctx, "continue");
            });
        }
        return out;
    }

    private <T extends Statement> void collect(List<T> statements, Node finallyBlock, List<Finding> out,
                                               AstCheckContext ctx, String keyword) {
        for (T s : statements) {
            if (directlyIn(s, finallyBlock)) {
                out.add(finding(ctx, s, "'" + keyword + "' inside finally silently discards any exception the try/catch was propagating.",
                        "Move this out of finally, or ensure the original exception is preserved (e.g. addSuppressed)."));
            }
        }
    }

    /** True unless a nested method/lambda/anonymous class sits between {@code s} and {@code finallyBlock}. */
    private boolean directlyIn(Node s, Node finallyBlock) {
        Node p = s.getParentNode().orElse(null);
        while (p != null && p != finallyBlock) {
            if (p instanceof LambdaExpr || p instanceof MethodDeclaration || p instanceof ObjectCreationExpr) {
                return false;
            }
            p = p.getParentNode().orElse(null);
        }
        return p == finallyBlock;
    }
}
