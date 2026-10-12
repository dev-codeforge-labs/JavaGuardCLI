package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-CONC-006 (catalog &sect;1.5): inside a singleton bean's method (not a
 * field initializer or a {@code @Bean} factory method), spawning a raw
 * {@code Thread} or an {@code Executors.new*} pool bypasses the container's
 * lifecycle - nothing stops it when the application shuts down, and a pool
 * created per call is never reused. An executor created and never
 * {@code shutdown()} in the same method leaks threads on every invocation.
 */
final class Conc006UnmanagedThreadsOrExecutors extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-CONC-006", "Unmanaged threads/executors", Category.CONCURRENCY, Severity.MAJOR);

    private static final Set<String> SHUTDOWN_METHODS = Set.of("shutdown", "shutdownNow");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        ctx.unit().findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).stream()
                .filter(AstSupport::isSingletonBean)
                .forEach(c -> checkClass(ctx, c, out));
        return out;
    }

    private void checkClass(AstCheckContext ctx, com.github.javaparser.ast.body.ClassOrInterfaceDeclaration c, List<Finding> out) {
        for (MethodDeclaration m : c.getMethods()) {
            if (AstSupport.hasAnnotation(m, "Bean")) {
                continue;
            }
            for (ObjectCreationExpr creation : m.findAll(ObjectCreationExpr.class)) {
                if (!creation.getType().getNameAsString().equals("Thread")) {
                    continue;
                }
                boolean startedInline = creation.getParentNode()
                        .filter(MethodCallExpr.class::isInstance)
                        .map(MethodCallExpr.class::cast)
                        .filter(call -> call.getNameAsString().equals("start"))
                        .isPresent();
                boolean startedViaVariable = !startedInline && creation.getParentNode()
                        .filter(VariableDeclarator.class::isInstance)
                        .map(VariableDeclarator.class::cast)
                        .filter(v -> m.findAll(MethodCallExpr.class).stream()
                                .anyMatch(call -> call.getNameAsString().equals("start")
                                        && call.getScope().filter(Expression::isNameExpr)
                                                .map(s -> s.asNameExpr().getNameAsString().equals(v.getNameAsString()))
                                                .orElse(false)))
                        .isPresent();
                if (startedInline || startedViaVariable) {
                    out.add(finding(ctx, creation,
                            "A raw Thread is started directly inside a singleton bean's method.",
                            "Use a managed ExecutorService/TaskExecutor bean instead of creating threads ad hoc."));
                }
            }
            for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
                if (!isExecutorsFactoryCall(call)) {
                    continue;
                }
                boolean shutInSameMethod = m.findAll(MethodCallExpr.class).stream()
                        .anyMatch(other -> SHUTDOWN_METHODS.contains(other.getNameAsString()));
                if (!shutInSameMethod) {
                    out.add(finding(ctx, call,
                            "An executor is created here but never shut down in this method.",
                            "Inject a shared, container-managed ExecutorService bean, or shut this one down (try-with-resources on AutoCloseable executors, or an explicit shutdown())."));
                }
            }
        }
    }

    private boolean isExecutorsFactoryCall(MethodCallExpr call) {
        Expression scope = call.getScope().orElse(null);
        return scope instanceof NameExpr ne && ne.getNameAsString().equals("Executors")
                && call.getNameAsString().startsWith("new");
    }
}
