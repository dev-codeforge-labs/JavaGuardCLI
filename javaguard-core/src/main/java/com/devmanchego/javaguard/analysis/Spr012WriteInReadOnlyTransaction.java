package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * JG-SPR-012 (catalog &sect;1.6): a write through a repository, {@code EntityManager}, Hibernate {@code Session} or
 * {@code JdbcTemplate} inside {@code @Transactional(readOnly = true)}. Depending on the provider and the driver the write is
 * silently not flushed (Hibernate sets {@code FlushMode.MANUAL}) or fails on a read-only connection; neither is what the
 * code means. The receiver must be a variable whose declared type is a data-access type.
 */
final class Spr012WriteInReadOnlyTransaction extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SPR-012", "Write in a read-only transaction", Category.SPRING_JPA, Severity.MAJOR);

    private static final Set<String> WRITES = Set.of("save", "saveAll", "saveAndFlush", "saveAllAndFlush", "persist", "merge", "remove",
            "delete", "deleteAll", "deleteById", "deleteAllById", "deleteInBatch", "deleteAllInBatch", "deleteAllByIdInBatch",
            "update", "batchUpdate", "insert", "saveOrUpdate");
    private static final List<String> WRITE_PREFIXES = List.of("deleteBy", "removeBy", "deleteAllBy", "update", "insert");

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
            Optional<AnnotationExpr> transactional = TransactionalSupport.effective(method);
            if (transactional.isEmpty() || !TransactionalSupport.readOnly(transactional.get())) {
                continue;
            }
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                if (isWrite(call.getNameAsString())
                        && call.getScope().flatMap(ResSupport::receiverType).filter(ResSupport::isDataAccessType).isPresent()) {
                    out.add(finding(ctx, call, ResSupport.describe(call) + " writes inside " + method.getNameAsString()
                            + "(), whose transaction is read-only: the change is not flushed, or the read-only connection rejects it.",
                            "Remove readOnly = true from this method's @Transactional, or move the write to a method with a read-write transaction."));
                }
            }
        }
        return out;
    }

    private static boolean isWrite(String name) {
        return WRITES.contains(name) || WRITE_PREFIXES.stream().anyMatch(p -> name.startsWith(p) && name.length() > p.length()
                && Character.isUpperCase(name.charAt(p.length())));
    }
}
