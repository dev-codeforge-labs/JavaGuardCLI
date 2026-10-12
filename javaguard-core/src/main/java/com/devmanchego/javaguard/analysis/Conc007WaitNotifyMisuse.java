package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayList;
import java.util.List;

/**
 * JG-CONC-007 (catalog &sect;1.5): {@code Object.wait()} must be called in a
 * {@code while} loop re-checking the condition, because spurious wakeups and
 * multiple waiters both make a single {@code if} unsafe; {@code notify()}
 * wakes only one waiter, which can starve others waiting on a different
 * condition - {@code notifyAll()} (or a {@code java.util.concurrent} primitive)
 * is almost always the safer choice.
 */
final class Conc007WaitNotifyMisuse extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-CONC-007", "wait/notify misuse", Category.CONCURRENCY, Severity.MAJOR);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (call.getArguments().size() > 2) {
                continue;
            }
            String name = call.getNameAsString();
            if (name.equals("wait") && !isInWhileLoop(call)) {
                out.add(finding(ctx, call, "wait() is not called inside a while loop re-checking its condition.",
                        "Wrap it as: while (!condition) { lock.wait(); } to survive spurious wakeups and multiple waiters."));
            } else if (name.equals("notify")) {
                out.add(finding(ctx, call, "notify() wakes a single arbitrary waiter, which can starve others.",
                        "Use notifyAll(), or a java.util.concurrent primitive (Condition, CountDownLatch, Semaphore)."));
            }
        }
        return out;
    }

    private boolean isInWhileLoop(Node n) {
        Node current = n;
        while (current != null) {
            if (current instanceof WhileStmt) {
                return true;
            }
            if (current instanceof MethodDeclaration || current instanceof ConstructorDeclaration
                    || current instanceof InitializerDeclaration || current instanceof TypeDeclaration) {
                return false;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }
}
