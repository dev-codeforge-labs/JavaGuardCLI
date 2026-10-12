package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.SynchronizedStmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JG-CONC-009 (catalog &sect;1.5): {@code field++}, {@code field--} and
 * {@code field += x} are read-modify-write - not atomic - on a {@code static}
 * field or an instance field of a singleton bean, unless the field is an
 * {@code Atomic*} holder or the update happens inside a {@code synchronized}
 * block/method.
 */
final class Conc009NonAtomicUpdateOfSharedField extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-CONC-009", "Non-atomic update of shared field", Category.CONCURRENCY, Severity.MAJOR);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (UnaryExpr u : ctx.unit().findAll(UnaryExpr.class)) {
            if (isIncrementOrDecrement(u)) {
                reportIfShared(ctx, u, u.getExpression(), out);
            }
        }
        for (AssignExpr a : ctx.unit().findAll(AssignExpr.class)) {
            if (a.getOperator() == AssignExpr.Operator.PLUS) {
                reportIfShared(ctx, a, a.getTarget(), out);
            }
        }
        return out;
    }

    private boolean isIncrementOrDecrement(UnaryExpr u) {
        return u.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT || u.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
                || u.getOperator() == UnaryExpr.Operator.PREFIX_DECREMENT || u.getOperator() == UnaryExpr.Operator.POSTFIX_DECREMENT;
    }

    private void reportIfShared(AstCheckContext ctx, Node site, Expression target, List<Finding> out) {
        String fieldName = fieldName(target);
        if (fieldName == null || isSynchronizedContext(site)) {
            return;
        }
        Optional<FieldDeclaration> field = declaringField(site, fieldName);
        if (field.isEmpty()) {
            return;
        }
        FieldDeclaration f = field.get();
        if (f.hasModifier(Modifier.Keyword.VOLATILE)) {
            return;
        }
        String typeName = AstSupport.simpleName(f.getElementType().asString());
        if (typeName.startsWith("Atomic")) {
            return;
        }
        boolean isStatic = f.hasModifier(Modifier.Keyword.STATIC);
        boolean sharedInstance = !isStatic && AstSupport.enclosingClass(site).filter(AstSupport::isSingletonBean).isPresent();
        if (!isStatic && !sharedInstance) {
            return;
        }
        out.add(finding(ctx, site, "'" + fieldName + "' is a shared field updated with a non-atomic read-modify-write.",
                "Use an AtomicInteger/AtomicLong, make the field volatile only if updates are already externally synchronized, or synchronize this update."));
    }

    private String fieldName(Expression e) {
        if (e instanceof NameExpr ne) {
            return ne.getNameAsString();
        }
        if (e instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr) {
            return fa.getNameAsString();
        }
        return null;
    }

    private Optional<FieldDeclaration> declaringField(Node site, String fieldName) {
        return AstSupport.enclosingClass(site).flatMap(c -> c.getFields().stream()
                .filter(f -> f.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(fieldName)))
                .findFirst());
    }

    private boolean isSynchronizedContext(Node n) {
        Node current = n;
        while (current != null) {
            if (current instanceof SynchronizedStmt) {
                return true;
            }
            if (current instanceof MethodDeclaration md && md.hasModifier(Modifier.Keyword.SYNCHRONIZED)) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }
}
