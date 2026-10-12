package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-PERF-005 (catalog &sect;1.3): {@code List.contains/indexOf/remove(Object)}
 * inside a loop - each call is O(n), turning the loop into O(n&sup2;).
 */
final class Perf005LinearLookupInLoop extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-PERF-005", "Linear lookup in loop", Category.PERFORMANCE, Severity.INFO);

    private static final Set<String> METHODS = Set.of("contains", "indexOf", "remove");
    private static final Set<String> LIST_TYPES = Set.of("List", "ArrayList", "LinkedList");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (!METHODS.contains(call.getNameAsString()) || call.getArguments().size() != 1 || !AstSupport.isInLoop(call)) {
                continue;
            }
            if (declaredListType(call).isPresent()) {
                out.add(finding(ctx, call, "List." + call.getNameAsString() + "() inside a loop is a linear scan on every call.",
                        "Use a HashSet/HashMap for membership tests, or index the collection before the loop."));
            }
        }
        return out;
    }

    private java.util.Optional<String> declaredListType(MethodCallExpr call) {
        return call.getScope().filter(Expression::isNameExpr).map(Expression::asNameExpr)
                .flatMap(AstSupport::declaredTypeSimpleName)
                .map(t -> t.contains("<") ? t.substring(0, t.indexOf('<')) : t)
                .filter(LIST_TYPES::contains);
    }
}
