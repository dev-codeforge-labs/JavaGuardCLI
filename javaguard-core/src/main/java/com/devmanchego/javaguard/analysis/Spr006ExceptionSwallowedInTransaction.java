package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-SPR-006 (catalog &sect;1.6): in a {@code @Transactional} method (or class), a {@code catch} that neither
 * rethrows nor calls {@code setRollbackOnly()}. Spring rolls back only when an exception leaves the method, so
 * the transaction commits whatever the failed statement left half done.
 */
final class Spr006ExceptionSwallowedInTransaction extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SPR-006", "Exception swallowed in transaction", Category.SPRING_JPA,
            Severity.MAJOR);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public Set<Framework> requiresAnyOf() {
        return Frameworks.SPRING;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodDeclaration method : ctx.unit().findAll(MethodDeclaration.class)) {
            ClassOrInterfaceDeclaration type = SpringSupport.enclosingType(method);
            boolean transactional = AstSupport.hasAnnotation(method, "Transactional")
                    || (type != null && AstSupport.hasAnnotation(type, "Transactional"));
            if (!transactional) {
                continue;
            }
            for (CatchClause catchClause : method.findAll(CatchClause.class)) {
                boolean rethrows = !catchClause.getBody().findAll(ThrowStmt.class).isEmpty();
                boolean marksRollback = catchClause.getBody().findAll(MethodCallExpr.class).stream()
                        .anyMatch(c -> c.getNameAsString().equals("setRollbackOnly"));
                if (!rethrows && !marksRollback) {
                    out.add(finding(ctx, catchClause, "The exception caught in the transactional method " + method.getNameAsString()
                            + "() is swallowed, so the transaction will commit.",
                            "Rethrow it, or call TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()."));
                }
            }
        }
        return out;
    }
}
