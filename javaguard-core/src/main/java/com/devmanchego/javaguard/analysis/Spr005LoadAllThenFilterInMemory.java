package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.IfStmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-SPR-005 (catalog &sect;1.6): {@code findAll()} with no {@code Pageable}/{@code Specification}/{@code Example}
 * argument whose result the same method then filters in memory - {@code .stream().filter(...)} on it, directly or
 * through a variable, or a loop with an {@code if}. The database should have done the filtering.
 */
final class Spr005LoadAllThenFilterInMemory extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SPR-005", "Load all then filter in memory", Category.SPRING_JPA,
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
            for (MethodCallExpr findAll : method.findAll(MethodCallExpr.class)) {
                if (findAll.getNameAsString().equals("findAll") && findAll.getArguments().isEmpty()
                        && filteredInMemory(findAll, method)) {
                    out.add(finding(ctx, findAll, "findAll() loads every row and the result is then filtered in memory.",
                            "Add a derived query or @Query with the condition, or pass a Specification / Pageable."));
                }
            }
        }
        return out;
    }

    private static boolean filteredInMemory(MethodCallExpr findAll, MethodDeclaration method) {
        // findAll().stream().filter(...)
        MethodCallExpr stream = parentCall(findAll, "stream", "parallelStream");
        if (stream != null && parentCall(stream, "filter") != null) {
            return true;
        }
        // for (T t : findAll()) { if (...) ... }
        if (findAll.getParentNode().orElse(null) instanceof ForEachStmt forEach && forEach.getIterable() == findAll) {
            return !forEach.getBody().findAll(IfStmt.class).isEmpty();
        }
        // List<T> all = findAll(); ... all.stream().filter(...) / for (T t : all) { if ... }
        if (findAll.getParentNode().orElse(null) instanceof VariableDeclarator declarator
                && declarator.getInitializer().filter(init -> init == findAll).isPresent()) {
            String variable = declarator.getNameAsString();
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                if (call.getNameAsString().equals("stream") && isVariable(call.getScope().orElse(null), variable)
                        && parentCall(call, "filter") != null) {
                    return true;
                }
            }
            for (ForEachStmt forEach : method.findAll(ForEachStmt.class)) {
                if (isVariable(forEach.getIterable(), variable) && !forEach.getBody().findAll(IfStmt.class).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The call that has {@code node} as its receiver and one of the given names, if that is node's parent. */
    private static MethodCallExpr parentCall(Node node, String... names) {
        if (node.getParentNode().orElse(null) instanceof MethodCallExpr parent
                && parent.getScope().filter(scope -> scope == node).isPresent()) {
            for (String name : names) {
                if (parent.getNameAsString().equals(name)) {
                    return parent;
                }
            }
        }
        return null;
    }

    private static boolean isVariable(Expression expression, String name) {
        return expression instanceof NameExpr n && n.getNameAsString().equals(name);
    }
}
