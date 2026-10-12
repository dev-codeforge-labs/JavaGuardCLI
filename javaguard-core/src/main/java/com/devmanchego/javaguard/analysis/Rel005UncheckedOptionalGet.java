package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;

/**
 * JG-REL-005 (catalog &sect;1.4): {@code Optional.get()} without a preceding
 * {@code isPresent()}/{@code isEmpty()} check on the same variable anywhere in
 * the method. {@code orElseThrow()} is deliberately not reported (it is
 * already an explicit, intentional choice).
 */
final class Rel005UncheckedOptionalGet extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-REL-005", "Unchecked Optional.get", Category.RELIABILITY, Severity.MAJOR);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals("get") || !call.getArguments().isEmpty()) {
                continue;
            }
            String name = call.getScope().filter(Expression::isNameExpr).map(s -> s.asNameExpr().getNameAsString()).orElse(null);
            if (name == null || !"Optional".equals(baseType(call))) {
                continue;
            }
            MethodDeclaration method = AstSupport.enclosingMethod(call).orElse(null);
            if (method != null && !isGuarded(method, name)) {
                out.add(finding(ctx, call, "'" + name + "'.get() is called without checking isPresent()/isEmpty() first.",
                        "Guard with isPresent(), or use orElseThrow()/orElse(...) to make the empty case explicit."));
            }
        }
        return out;
    }

    private String baseType(MethodCallExpr call) {
        return call.getScope().filter(Expression::isNameExpr).map(Expression::asNameExpr)
                .flatMap(AstSupport::declaredTypeSimpleName)
                .map(t -> t.contains("<") ? t.substring(0, t.indexOf('<')) : t)
                .orElse(null);
    }

    /** Whole-method check, not flow-sensitive to the exact call site (favors precision over exhaustive analysis). */
    private boolean isGuarded(MethodDeclaration method, String name) {
        return method.findAll(MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals("isPresent") || c.getNameAsString().equals("isEmpty"))
                .anyMatch(c -> c.getScope().filter(s -> s.isNameExpr() && s.asNameExpr().getNameAsString().equals(name)).isPresent());
    }
}
