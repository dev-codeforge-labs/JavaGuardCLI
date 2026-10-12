package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JG-PERF-001 (catalog &sect;1.3): {@code s += x} or {@code s = s + x} on a
 * {@code String} local/field declared outside the loop it runs in - each
 * iteration allocates a new String.
 */
final class Perf001StringConcatInLoop extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-PERF-001", "String concatenation in loop", Category.PERFORMANCE, Severity.MAJOR);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (AssignExpr a : ctx.unit().findAll(AssignExpr.class)) {
            if (!AstSupport.isInLoop(a) || !a.getTarget().isNameExpr()) {
                continue;
            }
            String name = a.getTarget().asNameExpr().getNameAsString();
            if (!isSelfConcatenation(a, name) || !"String".equals(AstSupport.declaredTypeSimpleName(a.getTarget().asNameExpr()).orElse(null))) {
                continue;
            }
            Optional<VariableDeclarator> declarator = findDeclarator(a, name);
            if (declarator.isPresent() && AstSupport.isInLoop(declarator.get())) {
                continue; // declared fresh inside the same loop body: not an accumulation across iterations
            }
            out.add(finding(ctx, a, "'" + name + "' is a String rebuilt by concatenation on every iteration.",
                    "Declare a StringBuilder before the loop, append inside it, and call toString() once after the loop."));
        }
        return out;
    }

    private boolean isSelfConcatenation(AssignExpr a, String name) {
        if (a.getOperator() == AssignExpr.Operator.PLUS) {
            return true;
        }
        if (a.getOperator() != AssignExpr.Operator.ASSIGN || !a.getValue().isBinaryExpr()) {
            return false;
        }
        BinaryExpr b = a.getValue().asBinaryExpr();
        return b.getOperator() == BinaryExpr.Operator.PLUS && isNamed(b.getLeft(), name);
    }

    private boolean isNamed(Expression e, String name) {
        return e.isNameExpr() && e.asNameExpr().getNameAsString().equals(name);
    }

    private Optional<VariableDeclarator> findDeclarator(AssignExpr a, String name) {
        return AstSupport.enclosingMethod(a).flatMap(m -> m.findAll(VariableDeclarator.class).stream()
                .filter(v -> v.getNameAsString().equals(name)).findFirst());
    }
}
