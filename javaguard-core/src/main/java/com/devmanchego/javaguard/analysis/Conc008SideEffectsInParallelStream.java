package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * JG-CONC-008 (catalog &sect;1.5): a lambda passed to {@code forEach}/{@code map}/
 * {@code peek} on a {@code parallelStream()}/{@code .parallel()} pipeline runs
 * concurrently on worker threads; mutating a plain (non-concurrent) collection
 * captured from outside the lambda races.
 */
final class Conc008SideEffectsInParallelStream extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-CONC-008", "Side effects in parallel stream", Category.CONCURRENCY, Severity.CRITICAL);

    private static final Set<String> ITERATION_METHODS = Set.of("forEach", "forEachOrdered", "map", "peek");
    private static final Set<String> MUTATING_METHODS = Set.of("add", "addAll", "put", "putAll", "remove", "removeAll", "clear");
    private static final Set<String> CONCURRENCY_SAFE_TYPES = Set.of(
            "ConcurrentHashMap", "ConcurrentSkipListMap", "ConcurrentSkipListSet", "CopyOnWriteArrayList",
            "CopyOnWriteArraySet", "ConcurrentLinkedQueue", "ConcurrentLinkedDeque", "ConcurrentMap");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (!ITERATION_METHODS.contains(call.getNameAsString()) || call.getArguments().isEmpty()) {
                continue;
            }
            if (!isParallelChain(call)) {
                continue;
            }
            if (!(call.getArgument(0) instanceof LambdaExpr lambda)) {
                continue;
            }
            for (MethodCallExpr inner : lambda.getBody().findAll(MethodCallExpr.class)) {
                if (!MUTATING_METHODS.contains(inner.getNameAsString())) {
                    continue;
                }
                Optional<NameExpr> target = inner.getScope().filter(Expression::isNameExpr).map(Expression::asNameExpr);
                if (target.isEmpty() || isDeclaredInside(lambda, target.get().getNameAsString())) {
                    continue;
                }
                String typeName = AstSupport.declaredTypeSimpleName(target.get()).orElse("");
                if (CONCURRENCY_SAFE_TYPES.contains(typeName)) {
                    continue;
                }
                out.add(finding(ctx, inner,
                        "'" + target.get().getNameAsString() + "' is mutated from a parallel stream lambda without synchronization.",
                        "Use a concurrent collection (ConcurrentHashMap, CopyOnWriteArrayList) or collect() instead of a shared side effect."));
            }
        }
        return out;
    }

    private boolean isParallelChain(MethodCallExpr call) {
        Expression current = call;
        while (current instanceof MethodCallExpr mc) {
            String name = mc.getNameAsString();
            if (name.equals("parallelStream") || name.equals("parallel")) {
                return true;
            }
            current = mc.getScope().orElse(null);
        }
        return false;
    }

    private boolean isDeclaredInside(LambdaExpr lambda, String name) {
        return lambda.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(name))
                || !lambda.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                        .filter(v -> v.getNameAsString().equals(name)).toList().isEmpty();
    }
}
