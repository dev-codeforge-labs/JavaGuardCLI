package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-AI-001 (catalog &sect;1.8): {@code System.out}/{@code System.err} print
 * calls left in production code are debug debris, not real logging - no log
 * level, no correlation id, and no way to redirect or disable them.
 * {@code public static void main} methods print by design and are exempt.
 *
 * <p>The catalog's {@code rules.JG-AI-001.allow} package-glob override
 * (default {@code **.cli.**}) is not yet backed by a config file in this
 * phase; as an interim default this rule instead exempts any file whose
 * relative path contains a {@code cli} directory segment, which covers the
 * documented default without requiring the general config plumbing. See
 * {@code .private/spec/02-RULES-CATALOG.md} &sect;1.8.
 */
final class Ai001ConsoleOutputInProductionCode extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-AI-001", "Console output in production code", Category.AI_DEBRIS, Severity.MAJOR);

    private static final Set<String> PRINT_METHODS = Set.of("print", "println", "printf", "write", "format");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        if (isCliPath(ctx.relativePath())) {
            return out;
        }
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (!PRINT_METHODS.contains(call.getNameAsString())) {
                continue;
            }
            String stream = consoleStream(call.getScope().orElse(null));
            if (stream == null || isInsideMain(call)) {
                continue;
            }
            out.add(finding(ctx, call, "System." + stream + "." + call.getNameAsString() + "(...) is a debug print left in production code.",
                    "Use a logger (SLF4J) with an appropriate level instead of printing to the console directly."));
        }
        return out;
    }

    private String consoleStream(Expression scope) {
        if (!(scope instanceof FieldAccessExpr fa) || !(fa.getScope() instanceof NameExpr ne) || !ne.getNameAsString().equals("System")) {
            return null;
        }
        String field = fa.getNameAsString();
        return (field.equals("out") || field.equals("err")) ? field : null;
    }

    private boolean isInsideMain(MethodCallExpr call) {
        return AstSupport.enclosingMethod(call)
                .filter(m -> m.getNameAsString().equals("main") && isMainSignature(m))
                .isPresent();
    }

    private boolean isMainSignature(MethodDeclaration m) {
        return m.isStatic() && m.getParameters().size() == 1
                && AstSupport.simpleName(m.getParameter(0).getTypeAsString()).equals("String[]");
    }

    private boolean isCliPath(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        return normalized.contains("/cli/") || normalized.startsWith("cli/");
    }
}
