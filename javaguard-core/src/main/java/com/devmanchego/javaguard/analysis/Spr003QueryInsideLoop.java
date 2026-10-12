package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * JG-SPR-003 (catalog &sect;1.6): a data-access call inside a loop - the N+1 query shape. The receiver is a
 * {@code *Repository}/{@code *Dao}/{@code *Mapper}, a {@code JdbcTemplate}, {@code NamedParameterJdbcTemplate},
 * {@code EntityManager} or {@code Session} (by declared type, or by variable name when the type is not visible).
 * The collection a {@code for-each} iterates over is evaluated once and is not "in the loop". INFO variant: the
 * getter of an entity's {@code @OneToMany}/{@code @ManyToMany} field called on the loop's element, which loads the
 * lazy collection once per element.
 */
final class Spr003QueryInsideLoop extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SPR-003", "Query inside loop (N+1)", Category.SPRING_JPA, Severity.MAJOR);

    private static final Set<String> DATA_ACCESS_TYPES = Set.of("JdbcTemplate", "NamedParameterJdbcTemplate", "EntityManager",
            "Session");
    private static final List<String> DATA_ACCESS_SUFFIXES = List.of("repository", "dao", "mapper", "jdbctemplate", "entitymanager");
    private static final Set<String> PLAIN_OBJECT_METHODS = Set.of("hashCode", "toString", "equals", "getClass");

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
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (!inLoop(call) || call.getScope().isEmpty()) {
                continue;
            }
            Expression receiver = call.getScope().get();
            Optional<NameExpr> variable = variableOf(receiver);
            if (variable.isEmpty()) {
                continue;
            }
            String typeName = AstSupport.declaredTypeSimpleName(variable.get()).orElse("");
            if (isDataAccess(typeName, variable.get().getNameAsString()) && !PLAIN_OBJECT_METHODS.contains(call.getNameAsString())) {
                out.add(finding(ctx, call, call.getNameAsString() + "() on " + variable.get().getNameAsString()
                        + " runs a query on every iteration of the loop.",
                        "Fetch what the loop needs in one query before it (findAllById, a join fetch, a batch)."));
            } else if (ctx.isEntity(typeName) && ctx.toManyGetters(typeName).contains(call.getNameAsString())
                    && call.getArguments().isEmpty()) {
                out.add(findingWithSeverity(ctx, call, Severity.INFO, call.getNameAsString() + "() loads the lazy collection of "
                        + typeName + " once per element of the loop.",
                        "Fetch the association with the parent query (join fetch / entity graph)."));
            }
        }
        return out;
    }

    private static boolean isDataAccess(String typeName, String variableName) {
        if (DATA_ACCESS_TYPES.contains(typeName) || typeName.endsWith("Repository") || typeName.endsWith("Dao")
                || typeName.endsWith("Mapper")) {
            return true;
        }
        String lower = variableName.toLowerCase(Locale.ROOT);
        return typeName.isEmpty() && DATA_ACCESS_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    /** {@code name} or {@code this.name}. */
    static Optional<NameExpr> variableOf(Expression receiver) {
        if (receiver instanceof NameExpr name) {
            return Optional.of(name);
        }
        if (receiver instanceof FieldAccessExpr field && field.getScope() instanceof ThisExpr) {
            NameExpr name = new NameExpr(field.getNameAsString());
            name.setParentNode(field);
            return Optional.of(name);
        }
        return Optional.empty();
    }

    /**
     * Lexically inside the body of a loop or of a lambda passed to a stream iteration method. A for-each's
     * iterable is evaluated once, so a query there is not repeated.
     */
    static boolean inLoop(Node node) {
        Node child = node;
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof ForEachStmt forEach) {
                if (child == forEach.getBody()) {
                    return true;
                }
            } else if (current instanceof ForStmt forStmt) {
                if (child == forStmt.getBody()) {
                    return true;
                }
            } else if (current instanceof WhileStmt || current instanceof DoStmt) {
                return true;
            } else if (current instanceof LambdaExpr && AstSupport.isInLoop(current)) {
                return true;
            }
            if (current instanceof MethodDeclaration || current instanceof ConstructorDeclaration
                    || current instanceof InitializerDeclaration || current instanceof TypeDeclaration) {
                return false;
            }
            child = current;
            current = current.getParentNode().orElse(null);
        }
        return false;
    }
}
