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
 * JG-RES-006 (catalog &sect;1.11): {@code CompletableFuture.supplyAsync/runAsync} with no executor, running blocking work
 * (a repository or JDBC call, a remote call, file reads, {@code Thread.sleep}). Without an executor the task goes to
 * {@code ForkJoinPool.commonPool()}, which has about as many threads as cores and is shared by the whole JVM, parallel
 * streams included: a few blocked tasks starve everything else.
 */
final class Res006BlockingOnCommonPool extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-RES-006", "Blocking work on the common pool", Category.RELIABILITY, Severity.MAJOR);

    private static final Set<String> ASYNC_STARTERS = Set.of("supplyAsync", "runAsync");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (!ASYNC_STARTERS.contains(call.getNameAsString()) || call.getArguments().size() != 1
                    || call.getScope().filter(s -> s.toString().equals("CompletableFuture")).isEmpty()) {
                continue;
            }
            Expression task = call.getArgument(0);
            boolean blocking = task.findAll(MethodCallExpr.class).stream().anyMatch(ResSupport::isBlockingCall);
            if (blocking) {
                out.add(finding(ctx, call, "CompletableFuture." + call.getNameAsString() + "(...) runs blocking work on the shared "
                        + "ForkJoinPool.commonPool().", "Pass a dedicated executor as the second argument (a bounded pool sized for the blocking calls)."));
            }
        }
        return out;
    }
}
