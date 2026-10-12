package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JG-RES-002 (catalog &sect;1.11): a blocking wait with no timeout - {@code Future.get()}, {@code CompletableFuture.join()},
 * {@code CountDownLatch.await()}, {@code Semaphore.acquire()}, {@code BlockingQueue.take()}, {@code Thread.join()} - on a
 * variable the file declares with such a type. One lost completion and the thread (a request thread, in a server) is gone
 * for ever. Not reported inside {@code main}, where waiting for the work to finish is the program.
 */
final class Res002UnboundedWait extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-RES-002", "Unbounded wait", Category.RELIABILITY, Severity.MAJOR);

    private static final Set<String> FUTURES = Set.of("Future", "CompletableFuture", "FutureTask", "ScheduledFuture", "ForkJoinTask",
            "RunnableFuture");
    private static final Set<String> QUEUES = Set.of("BlockingQueue", "LinkedBlockingQueue", "ArrayBlockingQueue",
            "PriorityBlockingQueue", "LinkedBlockingDeque", "BlockingDeque", "SynchronousQueue", "DelayQueue", "TransferQueue",
            "LinkedTransferQueue");
    private static final Map<String, Set<String>> WAITS_BY_METHOD = Map.of(
            "get", FUTURES,
            "join", Set.of("CompletableFuture", "Thread", "ForkJoinTask"),
            "await", Set.of("CountDownLatch", "CyclicBarrier", "Phaser"),
            "acquire", Set.of("Semaphore"),
            "take", QUEUES);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            Set<String> types = WAITS_BY_METHOD.get(call.getNameAsString());
            if (types == null || !call.getArguments().isEmpty() || call.getScope().isEmpty() || ResSupport.insideMain(call)) {
                continue;
            }
            Optional<String> waited = call.getScope().flatMap(ResSupport::receiverType).filter(types::contains);
            if (waited.isPresent() && FUTURES.contains(waited.get()) && (ownWork(call) || guardedByIsDone(call))) {
                continue;
            }
            waited.ifPresent(type ->
                    out.add(finding(ctx, call, call.getScope().get() + "." + call.getNameAsString() + "() waits with no timeout (" + type
                            + "): if the result never comes, this thread is lost.",
                            "Use the overload with a timeout (" + hint(call.getNameAsString()) + ") and handle the timeout.")));
        }
        return out;
    }

    /**
     * A future this method created itself - a local variable, or the variable of a loop over a local collection, that is not
     * the result of a remote client call: the method waits for work it started (typically tasks it submitted to an
     * executor), which ends when that work ends. Futures from fields and parameters, and from remote calls, are reported.
     */
    private static boolean ownWork(MethodCallExpr call) {
        Optional<String> name = call.getScope().flatMap(ResSupport::variableName);
        Optional<com.github.javaparser.ast.body.MethodDeclaration> method = AstSupport.enclosingMethod(call);
        if (name.isEmpty() || method.isEmpty() || call.getScope().get().isFieldAccessExpr()) {
            return false;
        }
        if (method.get().getParameters().stream().anyMatch(p -> p.getNameAsString().equals(name.get()))) {
            return false;
        }
        for (com.github.javaparser.ast.body.VariableDeclarator v : method.get().findAll(com.github.javaparser.ast.body.VariableDeclarator.class)) {
            if (!v.getNameAsString().equals(name.get())) {
                continue;
            }
            boolean loopVariable = v.getParentNode().flatMap(com.github.javaparser.ast.Node::getParentNode)
                    .filter(n -> n instanceof com.github.javaparser.ast.stmt.ForEachStmt).isPresent();
            boolean remote = v.getInitializer().map(i -> i.findAll(MethodCallExpr.class).stream().anyMatch(ResSupport::isRemoteCall))
                    .orElse(false);
            return loopVariable || !remote;
        }
        return false; // A field: the future comes from elsewhere.
    }

    /** {@code if (future.isDone()) { future.get(); }}: the result is already there. */
    private static boolean guardedByIsDone(MethodCallExpr call) {
        String receiver = call.getScope().map(Object::toString).orElse("");
        com.github.javaparser.ast.Node current = call;
        while (current.getParentNode().isPresent()) {
            current = current.getParentNode().get();
            if (current instanceof com.github.javaparser.ast.stmt.IfStmt ifStmt
                    && ifStmt.getCondition().toString().replace(" ", "").contains(receiver + ".isDone()")) {
                return true;
            }
            if (current instanceof com.github.javaparser.ast.body.MethodDeclaration) {
                return false;
            }
        }
        return false;
    }

    private static String hint(String method) {
        return switch (method) {
            case "get" -> "get(timeout, unit)";
            case "join" -> "orTimeout(...).join(), or Thread.join(millis)";
            case "await" -> "await(timeout, unit)";
            case "acquire" -> "tryAcquire(timeout, unit)";
            default -> "poll(timeout, unit)";
        };
    }
}
