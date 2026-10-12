package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;

import java.util.ArrayList;
import java.util.List;

/**
 * JG-REL-007 (catalog &sect;1.4): {@code ==}/{@code !=} where both sides are
 * {@code String}s (a literal, or a variable declared {@code String}), which
 * compares identity instead of content.
 */
final class Rel007StringEqualityWithDoubleEquals extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-REL-007", "String compared with ==", Category.RELIABILITY, Severity.CRITICAL);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public boolean mainOnly() {
        return false; // Catalog scope: all.
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (BinaryExpr b : ctx.unit().findAll(BinaryExpr.class)) {
            if ((b.getOperator() == BinaryExpr.Operator.EQUALS || b.getOperator() == BinaryExpr.Operator.NOT_EQUALS)
                    && isStringy(b.getLeft()) && isStringy(b.getRight())) {
                out.add(finding(ctx, b, "Both sides of '" + b.getOperator().asString() + "' are String; this compares identity, not content.",
                        "Use .equals(...) (or Objects.equals(a, b) if either side may be null)."));
            }
        }
        return out;
    }

    private boolean isStringy(Expression e) {
        if (e.isNullLiteralExpr()) {
            return false;
        }
        if (e.isStringLiteralExpr()) {
            return true;
        }
        return e.isNameExpr() && "String".equals(AstSupport.declaredTypeSimpleName(e.asNameExpr()).orElse(null));
    }
}
