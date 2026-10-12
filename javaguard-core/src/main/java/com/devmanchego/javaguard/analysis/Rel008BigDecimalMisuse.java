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
 * JG-REL-008 (catalog &sect;1.4): three distinct {@code BigDecimal} pitfalls -
 * constructing from a {@code double} (inherits its binary rounding error),
 * {@code equals} (scale-sensitive: {@code 1.0} != {@code 1.00}), and
 * {@code divide} without a rounding mode (throws on a non-terminating result).
 */
final class Rel008BigDecimalMisuse extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-REL-008", "BigDecimal misuse", Category.RELIABILITY, Severity.MAJOR);

    private static final Set<String> DOUBLE_TYPES = Set.of("double", "Double");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (ObjectCreationExpr creation : ctx.unit().findAll(ObjectCreationExpr.class)) {
            if (creation.getType().getNameAsString().equals("BigDecimal") && creation.getArguments().size() == 1
                    && isDoubleValued(creation.getArguments().get(0))) {
                out.add(finding(ctx, creation, "new BigDecimal(double) inherits the binary floating-point rounding error of the double.",
                        "Use BigDecimal.valueOf(double), or construct from a String literal."));
            }
        }
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            String type = DynamicStringBuilds.receiverTypeOf(call).orElse(null);
            if (!"BigDecimal".equals(type)) {
                continue;
            }
            if (call.getNameAsString().equals("equals")) {
                out.add(finding(ctx, call, "BigDecimal.equals() also compares scale (1.0 is not equal to 1.00).",
                        "Use compareTo(...) == 0 to compare numeric value regardless of scale."));
            } else if (call.getNameAsString().equals("divide") && call.getArguments().size() == 1) {
                out.add(finding(ctx, call, "divide(...) with no MathContext/RoundingMode throws on a non-terminating result.",
                        "Pass a RoundingMode or MathContext, e.g. divide(divisor, RoundingMode.HALF_UP)."));
            }
        }
        return out;
    }

    private boolean isDoubleValued(Expression e) {
        if (e.isDoubleLiteralExpr()) {
            return true;
        }
        return e.isNameExpr() && DOUBLE_TYPES.contains(AstSupport.declaredTypeSimpleName(e.asNameExpr()).orElse(""));
    }
}
