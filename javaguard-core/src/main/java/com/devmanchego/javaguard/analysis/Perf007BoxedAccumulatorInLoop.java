package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.UnaryExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-PERF-007 (catalog &sect;1.3): {@code ++}/{@code --}/compound-assignment
 * on a boxed ({@code Long}/{@code Integer}/{@code Double}/...) local inside a
 * loop - every update allocates a new wrapper instead of mutating a primitive.
 */
final class Perf007BoxedAccumulatorInLoop extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-PERF-007", "Boxed accumulator in loop", Category.PERFORMANCE, Severity.INFO);

    private static final Set<String> BOXED_TYPES = Set.of("Long", "Integer", "Double", "Float", "Short", "Byte");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (UnaryExpr u : ctx.unit().findAll(UnaryExpr.class)) {
            if (!AstSupport.isInLoop(u) || !isIncrementOrDecrement(u.getOperator())) {
                continue;
            }
            boxedTypeOf(u.getExpression()).ifPresent(type -> out.add(finding(ctx, u,
                    "A boxed " + type + " is incremented/decremented inside a loop.",
                    "Use a primitive (" + type.toLowerCase(java.util.Locale.ROOT).replace("integer", "int") + ") accumulator instead of the boxed type.")));
        }
        for (AssignExpr a : ctx.unit().findAll(AssignExpr.class)) {
            if (!AstSupport.isInLoop(a) || a.getOperator() == AssignExpr.Operator.ASSIGN) {
                continue;
            }
            boxedTypeOf(a.getTarget()).ifPresent(type -> out.add(finding(ctx, a,
                    "A boxed " + type + " is updated with a compound assignment inside a loop.",
                    "Use a primitive (" + type.toLowerCase(java.util.Locale.ROOT).replace("integer", "int") + ") accumulator instead of the boxed type.")));
        }
        return out;
    }

    private boolean isIncrementOrDecrement(UnaryExpr.Operator op) {
        return op == UnaryExpr.Operator.PREFIX_INCREMENT || op == UnaryExpr.Operator.POSTFIX_INCREMENT
                || op == UnaryExpr.Operator.PREFIX_DECREMENT || op == UnaryExpr.Operator.POSTFIX_DECREMENT;
    }

    private java.util.Optional<String> boxedTypeOf(Expression e) {
        return e.isNameExpr() ? AstSupport.declaredTypeSimpleName(e.asNameExpr()).filter(BOXED_TYPES::contains)
                : java.util.Optional.empty();
    }
}
