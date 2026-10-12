package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-PERF-009 (catalog &sect;1.3): the whole content of a file or stream read
 * into memory inside a loop - once per iteration instead of once overall.
 */
final class Perf009WholeStreamMaterialized extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-PERF-009", "Whole-file/stream materialized", Category.PERFORMANCE, Severity.INFO);

    private static final Set<String> FILES_METHODS = Set.of("readAllBytes", "readAllLines", "readString");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (!AstSupport.isInLoop(call)) {
                continue;
            }
            String name = call.getNameAsString();
            if (FILES_METHODS.contains(name) && isNamedScope(call, "Files")) {
                out.add(finding(ctx, call, "Files." + name + "() reads the whole file into memory on every iteration.",
                        "Read the file once, outside the loop, or stream it instead of materializing it repeatedly."));
            } else if (name.equals("readAllBytes") && call.getArguments().isEmpty() && !isNamedScope(call, "Files")) {
                out.add(finding(ctx, call, "readAllBytes() reads an entire stream into memory on every iteration.",
                        "Read the stream once, outside the loop, or process it incrementally instead."));
            }
        }
        return out;
    }

    private boolean isNamedScope(MethodCallExpr call, String name) {
        return call.getScope().filter(s -> s.isNameExpr() && s.asNameExpr().getNameAsString().equals(name)).isPresent();
    }
}
