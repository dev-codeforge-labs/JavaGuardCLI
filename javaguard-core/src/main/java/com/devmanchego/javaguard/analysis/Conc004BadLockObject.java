package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.SynchronizedStmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * JG-CONC-004 (catalog &sect;1.5): {@code synchronized} on a lock object other
 * programs can also lock on - a {@code String} literal (interned, so it is
 * effectively a JVM-wide lock), a boxed primitive (cached instances are
 * shared), {@code getClass()} (shared across subclasses), or a non-{@code final}
 * field (the lock object can change underneath concurrent callers).
 */
final class Conc004BadLockObject extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-CONC-004", "Bad lock object", Category.CONCURRENCY, Severity.MAJOR);

    private static final Set<String> BOXED_TYPES = Set.of(
            "Integer", "Long", "Short", "Byte", "Character", "Boolean", "Double", "Float");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (SynchronizedStmt sync : ctx.unit().findAll(SynchronizedStmt.class)) {
            Expression lock = sync.getExpression();
            String reason = badLockReason(ctx, lock);
            if (reason != null) {
                out.add(finding(ctx, sync, "This synchronized block locks on " + reason + ".",
                        "Lock on a private final Object dedicated to this purpose, or use java.util.concurrent locks."));
            }
        }
        return out;
    }

    private String badLockReason(AstCheckContext ctx, Expression lock) {
        if (lock.isStringLiteralExpr()) {
            return "a String literal (interned, effectively a JVM-wide lock)";
        }
        if (lock instanceof MethodCallExpr call && call.getNameAsString().equals("getClass") && call.getArguments().isEmpty()) {
            return "getClass() (shared across every instance/subclass)";
        }
        if (lock instanceof NameExpr ne) {
            Optional<String> type = AstSupport.declaredTypeSimpleName(ne);
            if (type.isPresent() && BOXED_TYPES.contains(type.get())) {
                return "a boxed " + type.get() + " (cached instances are shared)";
            }
            if (isNonFinalField(ctx, ne.getNameAsString())) {
                return "a non-final field (the lock identity can change under concurrent callers)";
            }
        }
        return null;
    }

    private boolean isNonFinalField(AstCheckContext ctx, String name) {
        for (ClassOrInterfaceDeclaration c : ctx.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            for (FieldDeclaration f : c.getFields()) {
                if (f.hasModifier(Modifier.Keyword.FINAL)) {
                    continue;
                }
                for (VariableDeclarator v : f.getVariables()) {
                    if (v.getNameAsString().equals(name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
