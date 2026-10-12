package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;

/**
 * JG-AI-002 (catalog &sect;1.8): {@code exception.printStackTrace()} writes
 * straight to the console with no log level, no correlation id, and no way
 * to route or disable it - a debugging leftover, not real error handling.
 */
final class Ai002PrintStackTrace extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-AI-002", "printStackTrace", Category.AI_DEBRIS, Severity.MAJOR);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (call.getNameAsString().equals("printStackTrace") && call.getArguments().size() <= 1) {
                out.add(finding(ctx, call, "printStackTrace() writes directly to the console instead of using a logger.",
                        "Log the exception through SLF4J (e.g. log.error(\"...\", e)) instead."));
            }
        }
        return out;
    }
}
