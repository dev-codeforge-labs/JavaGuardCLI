package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;

/** JG-PERF-006 (catalog &sect;1.3): {@code list.remove(0)} on an {@code ArrayList} in a loop shifts every remaining element. */
final class Perf006HeadRemovalArrayListInLoop extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-PERF-006", "Head removal on ArrayList in loop", Category.PERFORMANCE, Severity.INFO);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals("remove") || call.getArguments().size() != 1 || !AstSupport.isInLoop(call)) {
                continue;
            }
            boolean removesIndexZero = call.getArgument(0).isIntegerLiteralExpr()
                    && call.getArgument(0).asIntegerLiteralExpr().asNumber().intValue() == 0;
            boolean onArrayList = call.getScope().filter(Expression::isNameExpr).map(Expression::asNameExpr)
                    .flatMap(AstSupport::declaredTypeSimpleName).filter(t -> t.startsWith("ArrayList")).isPresent();
            if (removesIndexZero && onArrayList) {
                out.add(finding(ctx, call, "ArrayList.remove(0) inside a loop shifts every remaining element down.",
                        "Use an ArrayDeque (removeFirst()) when repeatedly removing from the front."));
            }
        }
        return out;
    }
}
