package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-RES-005 (catalog &sect;1.11): a thread pool or queue with no bound, so that load turns into memory or threads until the
 * process dies. {@code Executors.newCachedThreadPool()} (unbounded threads) and a {@code ThreadPoolExecutor} given a
 * {@code LinkedBlockingQueue}/{@code LinkedBlockingDeque}/{@code PriorityBlockingQueue} with no capacity are MAJOR;
 * {@code newFixedThreadPool}/{@code newSingleThreadExecutor} (bounded threads, unbounded queue) are INFO, because they are
 * everywhere and only matter when the producers can outrun the consumers. Virtual-thread executors are not reported.
 */
final class Res005UnboundedThreadPool extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-RES-005", "Unbounded thread pool or queue", Category.RELIABILITY, Severity.MAJOR);

    private static final Set<String> UNBOUNDED_QUEUES = Set.of("LinkedBlockingQueue", "LinkedBlockingDeque", "PriorityBlockingQueue");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (call.getScope().filter(s -> s.toString().equals("Executors")).isEmpty() || ResSupport.insideMain(call)) {
                continue;
            }
            switch (call.getNameAsString()) {
                case "newCachedThreadPool" -> out.add(finding(ctx, call, "Executors.newCachedThreadPool() creates a new thread for every task "
                        + "that finds none idle, without limit.", "Use a ThreadPoolExecutor with a maximum size and a bounded queue, "
                        + "and a rejection policy."));
                case "newFixedThreadPool", "newSingleThreadExecutor" -> out.add(findingWithSeverity(ctx, call, Severity.INFO,
                        "Executors." + call.getNameAsString() + "() queues tasks without limit.",
                        "Use a ThreadPoolExecutor with a bounded queue if producers can outrun the workers."));
                default -> { }
            }
        }
        for (ObjectCreationExpr creation : ctx.unit().findAll(ObjectCreationExpr.class)) {
            if (!creation.getType().getNameAsString().equals("ThreadPoolExecutor") || ResSupport.insideMain(creation)) {
                continue;
            }
            for (Expression argument : creation.getArguments()) {
                if (argument instanceof ObjectCreationExpr queue && queue.getArguments().isEmpty()
                        && UNBOUNDED_QUEUES.contains(queue.getType().getNameAsString())) {
                    out.add(finding(ctx, creation, "This ThreadPoolExecutor uses a " + queue.getType().getNameAsString()
                            + " with no capacity: the queue grows until memory runs out.",
                            "Give the queue a capacity (new ArrayBlockingQueue<>(n) or new LinkedBlockingQueue<>(n)) and a rejection policy."));
                    break;
                }
            }
        }
        return out;
    }
}
