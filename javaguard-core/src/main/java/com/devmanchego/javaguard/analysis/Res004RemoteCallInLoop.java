package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;

/**
 * JG-RES-004 (catalog &sect;1.11): a call to a remote system inside a loop - the remote N+1. Ten items are ten requests; a
 * hundred thousand are a hundred thousand, each with its own latency and chance of failing. Sibling of JG-SPR-003, which
 * does the same for data-access receivers.
 */
final class Res004RemoteCallInLoop extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-RES-004", "Remote call in loop", Category.RELIABILITY, Severity.MAJOR);

    /** {@code KafkaTemplate.send} only enqueues: the producer batches the records. */
    private static boolean asynchronousSend(MethodCallExpr call) {
        return call.getScope().flatMap(ResSupport::receiverType).filter(t -> t.equals("KafkaTemplate")).isPresent();
    }

    /**
     * The "loop" is a lambda of a reactive operator ({@code flatMap}, {@code map}...) on the result of another remote call:
     * a {@code Mono} continuation runs once, it does not iterate.
     */
    private static boolean reactiveContinuation(MethodCallExpr call) {
        java.util.Optional<com.github.javaparser.ast.expr.LambdaExpr> lambda = call.findAncestor(com.github.javaparser.ast.expr.LambdaExpr.class);
        if (lambda.isEmpty() || !(lambda.get().getParentNode().orElse(null) instanceof MethodCallExpr operator)) {
            return false;
        }
        com.github.javaparser.ast.expr.Expression root = operator;
        while (root.isMethodCallExpr() && root.asMethodCallExpr().getScope().isPresent()
                && root.asMethodCallExpr().getScope().get().isMethodCallExpr()) {
            root = root.asMethodCallExpr().getScope().get();
        }
        return root.isMethodCallExpr() && root != operator && ResSupport.isRemoteCall(root.asMethodCallExpr());
    }

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (ResSupport.isRemoteCall(call) && Spr003QueryInsideLoop.inLoop(call) && !ResSupport.insideMain(call)
                    && !asynchronousSend(call) && !reactiveContinuation(call)) {
                out.add(finding(ctx, call, ResSupport.describe(call) + " is a remote call made on every iteration of a loop.",
                        "Use a batch endpoint, fetch what the loop needs in one request, or run the calls concurrently with a bounded pool."));
            }
        }
        return out;
    }
}
