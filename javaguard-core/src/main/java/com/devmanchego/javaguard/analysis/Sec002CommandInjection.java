package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-SEC-002 (catalog &sect;1.2): a command built from non-constant input,
 * either {@code Runtime.exec(String)} or a {@code ProcessBuilder} invoking a
 * shell ({@code sh -c}, {@code cmd /c}) with a dynamic command line.
 */
final class Sec002CommandInjection extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SEC-002", "Command built from variable input", Category.SECURITY, Severity.CRITICAL);

    private static final Set<String> SHELLS = Set.of("sh", "cmd", "bash", "powershell");
    private static final Set<String> SHELL_FLAGS = Set.of("-c", "/c", "-command");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        checkRuntimeExec(ctx, out);
        checkProcessBuilder(ctx, out);
        return out;
    }

    private void checkRuntimeExec(AstCheckContext ctx, List<Finding> out) {
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals("exec") || call.getArguments().size() != 1) {
                continue;
            }
            boolean onRuntime = call.getScope()
                    .filter(Expression::isMethodCallExpr)
                    .map(Expression::asMethodCallExpr)
                    .filter(s -> s.getNameAsString().equals("getRuntime"))
                    .flatMap(MethodCallExpr::getScope)
                    .filter(Expression::isNameExpr)
                    .map(s -> s.asNameExpr().getNameAsString())
                    .filter("Runtime"::equals)
                    .isPresent();
            if (onRuntime && DynamicStringBuilds.isNonConstant(call.getArgument(0))) {
                out.add(finding(ctx, call, "A command line built dynamically is passed to Runtime.exec(String).",
                        "Pass the command and each argument as separate array elements, or validate/allow-list the input."));
            }
        }
    }

    private void checkProcessBuilder(AstCheckContext ctx, List<Finding> out) {
        for (ObjectCreationExpr creation : ctx.unit().findAll(ObjectCreationExpr.class)) {
            if (creation.getType().getNameAsString().equals("ProcessBuilder") && hasShellInjection(creation.getArguments())) {
                out.add(shellFinding(ctx, creation));
            }
        }
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals("command")) {
                continue;
            }
            boolean onProcessBuilder = DynamicStringBuilds.receiverTypeOf(call).filter("ProcessBuilder"::equals).isPresent();
            if (onProcessBuilder && hasShellInjection(call.getArguments())) {
                out.add(shellFinding(ctx, call));
            }
        }
    }

    private Finding shellFinding(AstCheckContext ctx, com.github.javaparser.ast.Node at) {
        return finding(ctx, at, "A shell is invoked with a dynamically built command line.",
                "Avoid invoking a shell; run the target program directly with its arguments as separate elements. "
                        + "If a shell is unavoidable, validate/allow-list the dynamic part.");
    }

    private boolean hasShellInjection(NodeList<Expression> args) {
        for (int i = 0; i + 2 < args.size(); i++) {
            if (isConstantOneOf(args.get(i), SHELLS) && isConstantOneOf(args.get(i + 1), SHELL_FLAGS)
                    && DynamicStringBuilds.isNonConstant(args.get(i + 2))) {
                return true;
            }
        }
        return false;
    }

    private boolean isConstantOneOf(Expression e, Set<String> values) {
        return e.isStringLiteralExpr() && values.contains(e.asStringLiteralExpr().asString().toLowerCase());
    }
}
