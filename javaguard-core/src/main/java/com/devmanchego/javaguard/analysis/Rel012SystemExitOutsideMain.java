package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;

/**
 * JG-REL-012 (catalog &sect;1.4): {@code System.exit}/{@code Runtime.halt}
 * outside {@code public static void main(String[])} - it kills the whole JVM,
 * including a test runner, an application server, or a caller that expected
 * to keep running.
 */
final class Rel012SystemExitOutsideMain extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-REL-012", "System.exit in non-main code", Category.RELIABILITY, Severity.MAJOR);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            boolean isExit = name.equals("exit") && isNamedScope(call, "System");
            boolean isHalt = name.equals("halt") && call.getScope().filter(this::isRuntimeGetRuntime).isPresent();
            if ((isExit || isHalt) && !AstSupport.enclosingMethod(call).filter(this::isMainMethod).isPresent()) {
                out.add(finding(ctx, call, (isExit ? "System.exit" : "Runtime.halt") + "() terminates the whole JVM.",
                        "Throw an exception or return an error result instead; let the entry point decide whether to exit."));
            }
        }
        return out;
    }

    private boolean isMainMethod(MethodDeclaration m) {
        return m.getNameAsString().equals("main") && m.hasModifier(Modifier.Keyword.PUBLIC)
                && m.hasModifier(Modifier.Keyword.STATIC) && m.getParameters().size() == 1;
    }

    private boolean isRuntimeGetRuntime(Expression scope) {
        return scope.isMethodCallExpr() && scope.asMethodCallExpr().getNameAsString().equals("getRuntime")
                && isNamedScope(scope.asMethodCallExpr(), "Runtime");
    }

    private boolean isNamedScope(MethodCallExpr call, String name) {
        return call.getScope().filter(s -> s.isNameExpr() && s.asNameExpr().getNameAsString().equals(name)).isPresent();
    }
}
