package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;

/**
 * JG-SEC-014 (catalog &sect;1.2): a redirect target built from non-constant
 * input, either the Spring MVC {@code "redirect:" + <param>} view-name
 * convention or a direct {@code sendRedirect(<param>)} call.
 */
final class Sec014OpenRedirect extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SEC-014", "Open redirect", Category.SECURITY, Severity.MAJOR);
    private static final String REDIRECT_PREFIX = "redirect:";

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (BinaryExpr b : ctx.unit().findAll(BinaryExpr.class)) {
            if (b.getOperator() == BinaryExpr.Operator.PLUS && b.getLeft().isStringLiteralExpr()
                    && b.getLeft().asStringLiteralExpr().asString().equals(REDIRECT_PREFIX)
                    && DynamicStringBuilds.isNonConstant(b.getRight())) {
                out.add(finding(ctx, b, "A redirect view name is built from a non-constant value after \"redirect:\".",
                        "Validate the target against an allow-list of known paths before redirecting."));
            }
        }
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (call.getNameAsString().equals("sendRedirect") && call.getArguments().size() == 1
                    && DynamicStringBuilds.isNonConstant(call.getArgument(0))) {
                out.add(finding(ctx, call, "sendRedirect() is called with a non-constant target.",
                        "Validate the target against an allow-list of known paths before redirecting."));
            }
        }
        return out;
    }
}
