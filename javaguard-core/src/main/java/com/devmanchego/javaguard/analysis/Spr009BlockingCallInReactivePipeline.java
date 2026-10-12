package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JG-SPR-009 (catalog &sect;1.6): a blocking call inside a lambda handed to a Reactor operator -
 * {@code block()}/{@code blockFirst()}/{@code blockLast()}, {@code Thread.sleep}, JDBC ({@code JdbcTemplate},
 * {@code EntityManager}) or, when Spring Data JPA is on the module, a non-reactive {@code *Repository}. It
 * stalls the event-loop thread the whole pipeline runs on. A lambda belongs to a reactive pipeline when the
 * operator it is passed to is called on {@code Mono}/{@code Flux}, directly or through a chain, or on a variable
 * declared as one.
 */
final class Spr009BlockingCallInReactivePipeline extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SPR-009", "Blocking call in reactive pipeline", Category.SPRING_JPA,
            Severity.MAJOR);

    private static final Set<String> REACTIVE_TYPES = Set.of("Mono", "Flux");
    private static final Set<String> BLOCKING_METHODS = Set.of("block", "blockFirst", "blockLast", "blockOptional");
    private static final Set<String> JDBC_TYPES = Set.of("JdbcTemplate", "NamedParameterJdbcTemplate", "EntityManager",
            "Connection", "DataSource", "Statement", "PreparedStatement");

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
        Map<MethodCallExpr, String> blocking = new IdentityHashMap<>();
        for (LambdaExpr lambda : ctx.unit().findAll(LambdaExpr.class)) {
            if (!isReactiveOperatorArgument(lambda)) {
                continue;
            }
            for (MethodCallExpr call : lambda.findAll(MethodCallExpr.class)) {
                String why = blockingReason(call, ctx);
                if (why != null) {
                    blocking.putIfAbsent(call, why);
                }
            }
        }
        List<Finding> out = new ArrayList<>();
        blocking.forEach((call, why) -> out.add(finding(ctx, call, why + " inside a reactive pipeline blocks its event-loop thread.",
                "Use the reactive equivalent, or move the blocking work to Schedulers.boundedElastic() "
                        + "(Mono.fromCallable(...).subscribeOn(...)).")));
        return out;
    }

    private static boolean isReactiveOperatorArgument(LambdaExpr lambda) {
        if (!(lambda.getParentNode().orElse(null) instanceof MethodCallExpr operator) || !operator.getArguments().contains(lambda)) {
            return false;
        }
        Expression scope = operator.getScope().orElse(null);
        while (scope != null) {
            if (scope instanceof NameExpr name) {
                return REACTIVE_TYPES.contains(name.getNameAsString())
                        || AstSupport.declaredTypeSimpleName(name).map(REACTIVE_TYPES::contains).orElse(false);
            }
            if (scope instanceof MethodCallExpr chained) {
                scope = chained.getScope().orElse(null);
            } else {
                return false;
            }
        }
        return false;
    }

    private static String blockingReason(MethodCallExpr call, AstCheckContext ctx) {
        String name = call.getNameAsString();
        if (BLOCKING_METHODS.contains(name) && call.getArguments().size() <= 1) {
            return name + "()";
        }
        if (name.equals("sleep") && call.getScope().filter(s -> s.toString().equals("Thread")).isPresent()) {
            return "Thread.sleep()";
        }
        if (call.getScope().orElse(null) instanceof NameExpr receiver) {
            String type = AstSupport.declaredTypeSimpleName(receiver).orElse("");
            if (JDBC_TYPES.contains(type)) {
                return name + "() on the blocking " + type;
            }
            if (type.endsWith("Repository") && !type.contains("Reactive") && ctx.frameworks().contains(Framework.SPRING_DATA_JPA)) {
                return name + "() on the non-reactive " + type;
            }
        }
        return null;
    }
}
