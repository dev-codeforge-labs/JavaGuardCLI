package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JG-CONC-003 (catalog &sect;1.5): the classic double-checked-locking idiom -
 * {@code if (field == null) synchronized (lock) { if (field == null) field = new ...(); } } -
 * is broken unless {@code field} is {@code volatile}, because a reader can
 * observe a partially-constructed object through instruction reordering.
 */
final class Conc003DoubleCheckedLockingWithoutVolatile extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-CONC-003", "Double-checked locking without volatile", Category.CONCURRENCY, Severity.CRITICAL);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (IfStmt outer : ctx.unit().findAll(IfStmt.class)) {
            Optional<String> outerField = nullCheckedField(outer.getCondition());
            if (outerField.isEmpty() || outer.getElseStmt().isPresent()) {
                continue;
            }
            for (SynchronizedStmt sync : outer.getThenStmt().findAll(SynchronizedStmt.class)) {
                for (IfStmt inner : sync.getBody().findAll(IfStmt.class)) {
                    Optional<String> innerField = nullCheckedField(inner.getCondition());
                    if (innerField.isEmpty() || !innerField.get().equals(outerField.get())) {
                        continue;
                    }
                    boolean assignsField = !inner.getThenStmt().findAll(AssignExpr.class).stream()
                            .filter(a -> targetsName(a, innerField.get())).toList().isEmpty();
                    if (!assignsField) {
                        continue;
                    }
                    if (isVolatile(ctx, innerField.get())) {
                        continue;
                    }
                    out.add(finding(ctx, outer,
                            "'" + innerField.get() + "' is used in double-checked locking but is not volatile.",
                            "Declare the field volatile, or use a holder class / initialize-on-demand pattern instead."));
                }
            }
        }
        return out;
    }

    private Optional<String> nullCheckedField(Expression condition) {
        if (!(condition instanceof BinaryExpr b) || b.getOperator() != BinaryExpr.Operator.EQUALS) {
            return Optional.empty();
        }
        if (b.getRight().isNullLiteralExpr() && b.getLeft().isNameExpr()) {
            return Optional.of(b.getLeft().asNameExpr().getNameAsString());
        }
        if (b.getLeft().isNullLiteralExpr() && b.getRight().isNameExpr()) {
            return Optional.of(b.getRight().asNameExpr().getNameAsString());
        }
        return Optional.empty();
    }

    private boolean targetsName(AssignExpr a, String name) {
        return a.getTarget().isNameExpr() && a.getTarget().asNameExpr().getNameAsString().equals(name);
    }

    private boolean isVolatile(AstCheckContext ctx, String fieldName) {
        for (ClassOrInterfaceDeclaration c : ctx.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            for (FieldDeclaration f : c.getFields()) {
                for (VariableDeclarator v : f.getVariables()) {
                    if (v.getNameAsString().equals(fieldName)) {
                        return f.hasModifier(Modifier.Keyword.VOLATILE);
                    }
                }
            }
        }
        return false;
    }
}
