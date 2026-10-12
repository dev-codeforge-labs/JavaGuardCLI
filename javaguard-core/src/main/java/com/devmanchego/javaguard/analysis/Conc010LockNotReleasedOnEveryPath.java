package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * JG-CONC-010 (catalog &sect;1.5): {@code lock.lock()} whose matching {@code unlock()} is not in the {@code finally} of a
 * {@code try} that starts right after it. Any exception between the two leaves the lock held for ever and every other
 * thread that needs it blocks. The receiver is a variable the file declares as a {@code Lock}/{@code ReentrantLock}/read or
 * write lock, or a {@code readLock()}/{@code writeLock()} call. Not reported when the method never unlocks that lock at all
 * (acquire and release in different methods is a design, not a slip) or when the lock call is itself inside a
 * {@code try} whose {@code finally} unlocks.
 */
final class Conc010LockNotReleasedOnEveryPath extends AbstractAstCheck {

    static final RuleInfo INFO = new RuleInfo("JG-CONC-010", "Lock not released on every path", Category.CONCURRENCY, Severity.CRITICAL,
            "ast", "F");

    private static final Set<String> LOCK_TYPES = Set.of("Lock", "ReentrantLock", "ReadLock", "WriteLock", "StampedLock");
    private static final Set<String> ACQUIRING = Set.of("lock", "lockInterruptibly");
    private static final Set<String> WRAPPER_METHODS = Set.of("lock", "lockInterruptibly", "acquire", "enter", "begin", "open", "start");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (!ACQUIRING.contains(call.getNameAsString()) || !call.getArguments().isEmpty() || call.getScope().isEmpty()
                    || !(call.getParentNode().orElse(null) instanceof ExpressionStmt statement) || !isLock(call.getScope().get())) {
                continue;
            }
            Optional<MethodDeclaration> method = AstSupport.enclosingMethod(call);
            if (method.isEmpty() || WRAPPER_METHODS.contains(method.get().getNameAsString())) {
                continue;
            }
            String lock = call.getScope().get().toString();
            if (!unlocks(method.get(), lock) || insideTryThatUnlocks(call, lock) || nextIsTryThatUnlocks(statement, lock)) {
                continue;
            }
            out.add(finding(ctx, call, lock + "." + call.getNameAsString() + "() is not followed by a try/finally that calls " + lock
                    + ".unlock(): an exception in between leaves the lock held for ever.",
                    "Write " + lock + ".lock(); try { ... } finally { " + lock + ".unlock(); } with the try immediately after the lock call."));
        }
        return out;
    }

    private static boolean isLock(Expression scope) {
        if (scope instanceof MethodCallExpr call) {
            return call.getArguments().isEmpty() && (call.getNameAsString().equals("readLock") || call.getNameAsString().equals("writeLock"));
        }
        return ResSupport.receiverType(scope).filter(LOCK_TYPES::contains).isPresent();
    }

    private static boolean unlocks(MethodDeclaration method, String lock) {
        return method.findAll(MethodCallExpr.class).stream().anyMatch(c -> isUnlock(c, lock));
    }

    private static boolean isUnlock(MethodCallExpr call, String lock) {
        return call.getNameAsString().equals("unlock") && call.getScope().filter(s -> s.toString().equals(lock)).isPresent();
    }

    private static boolean finallyUnlocks(TryStmt tryStmt, String lock) {
        return tryStmt.getFinallyBlock().map(f -> f.findAll(MethodCallExpr.class).stream().anyMatch(c -> isUnlock(c, lock))).orElse(false);
    }

    private static boolean nextIsTryThatUnlocks(ExpressionStmt statement, String lock) {
        Optional<Node> parent = statement.getParentNode();
        if (parent.isEmpty() || !(parent.get() instanceof BlockStmt block)) {
            return false;
        }
        List<Statement> statements = block.getStatements();
        int index = statements.indexOf(statement);
        return index >= 0 && index + 1 < statements.size() && statements.get(index + 1) instanceof TryStmt tryStmt
                && finallyUnlocks(tryStmt, lock);
    }

    private static boolean insideTryThatUnlocks(Node node, String lock) {
        Node child = node;
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TryStmt tryStmt && child == tryStmt.getTryBlock() && finallyUnlocks(tryStmt, lock)) {
                return true;
            }
            if (current instanceof MethodDeclaration) {
                return false;
            }
            child = current;
            current = current.getParentNode().orElse(null);
        }
        return false;
    }
}
