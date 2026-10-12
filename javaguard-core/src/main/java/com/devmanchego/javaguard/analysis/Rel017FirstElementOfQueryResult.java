package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * JG-REL-017 (catalog &sect;1.4): {@code get(0)}, {@code getFirst()} or {@code iterator().next()} on the result of a
 * repository / {@code JdbcTemplate} / {@code EntityManager} call, directly or through a local variable, in a method that never
 * asks whether the result is empty. An empty result throws {@code IndexOutOfBoundsException} or
 * {@code NoSuchElementException}, which a test database that always has a row never shows.
 */
final class Rel017FirstElementOfQueryResult extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-REL-017", "First element of a query result", Category.RELIABILITY, Severity.MAJOR);

    private static final Set<String> EMPTINESS = Set.of("isEmpty", "isNotEmpty", "size", "isPresent", "stream", "forEach", "hasNext",
            "hasSize", "isNullOrEmpty", "isEmptyOrNull");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (!isFirst(call) || call.getScope().isEmpty()) {
                continue;
            }
            Expression source = call.getScope().get();
            if (call.getNameAsString().equals("next") && source.isMethodCallExpr()
                    && source.asMethodCallExpr().getNameAsString().equals("iterator")) {
                source = source.asMethodCallExpr().getScope().orElse(source);
            }
            if ((isQuery(source) || fromVariable(call, source)) && !handled(call)) {
                out.add(finding(ctx, call, "The first element of a query result is read without checking that the result is not empty.",
                        "Check isEmpty() first, or use a query that returns Optional (findFirstBy..., getFirst on a Optional-returning method)."));
            }
        }
        return out;
    }

    /** Inside a {@code try} that catches what an empty result throws: the empty case is handled on purpose. */
    private static boolean handled(MethodCallExpr call) {
        return call.findAncestor(com.github.javaparser.ast.stmt.TryStmt.class).filter(t -> t.getCatchClauses().stream()
                .anyMatch(c -> c.getParameter().getType().asString().matches(".*(IndexOutOfBounds|NoSuchElement|EmptyResultDataAccess)Exception.*")))
                .isPresent();
    }

    private static boolean isFirst(MethodCallExpr call) {
        return call.getNameAsString().equals("get") && call.getArguments().size() == 1 && call.getArgument(0).isIntegerLiteralExpr()
                && call.getArgument(0).asIntegerLiteralExpr().asNumber().intValue() == 0
                || call.getNameAsString().equals("getFirst") && call.getArguments().isEmpty()
                || call.getNameAsString().equals("next") && call.getArguments().isEmpty()
                && call.getScope().filter(sc -> sc.isMethodCallExpr() && sc.asMethodCallExpr().getNameAsString().equals("iterator")).isPresent();
    }

    private static boolean isQuery(Expression e) {
        if (!e.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr q = e.asMethodCallExpr();
        if (q.getNameAsString().equals("getResultList") || q.getNameAsString().equals("list")) {
            // entityManager.createQuery(...).getResultList(): the chain's root is the data-access variable.
            Expression root = q;
            while (root.isMethodCallExpr() && root.asMethodCallExpr().getScope().isPresent()) {
                root = root.asMethodCallExpr().getScope().get();
            }
            return ResSupport.receiverType(root).filter(ResSupport::isDataAccessType).isPresent();
        }
        String name = q.getNameAsString();
        boolean listReturning = !name.equals("findById") && !name.startsWith("findFirst") && !name.startsWith("findTop")
                && !name.equals("queryForObject") && !name.equals("getSingleResult") && !name.equals("find")
                && !name.startsWith("getReference");
        return listReturning && q.getScope().flatMap(ResSupport::receiverType).filter(ResSupport::isDataAccessType).isPresent();
    }

    /** A local initialised from a query call and never asked about emptiness in the same method. */
    private static boolean fromVariable(MethodCallExpr call, Expression source) {
        Optional<String> name = ResSupport.variableName(source);
        Optional<MethodDeclaration> method = AstSupport.enclosingMethod(call);
        if (name.isEmpty() || method.isEmpty()) {
            return false;
        }
        boolean fromQuery = method.get().findAll(VariableDeclarator.class).stream()
                .filter(v -> v.getNameAsString().equals(name.get()))
                .anyMatch(v -> v.getInitializer().filter(Rel017FirstElementOfQueryResult::isQuery).isPresent());
        // list.isEmpty(), and also CollectionUtils.isEmpty(list) / Objects-style helpers that take the list as argument.
        boolean checked = method.get().findAll(MethodCallExpr.class).stream().anyMatch(c -> EMPTINESS.contains(c.getNameAsString())
                && (c.getScope().flatMap(ResSupport::variableName).equals(name)
                || c.getArguments().stream().anyMatch(a -> a.isNameExpr() && a.asNameExpr().getNameAsString().equals(name.get()))));
        return fromQuery && !checked;
    }
}
