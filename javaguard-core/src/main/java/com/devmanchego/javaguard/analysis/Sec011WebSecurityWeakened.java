package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * JG-SEC-011 (catalog &sect;1.2): Spring Security configuration weakened.
 * Whether the weakening was <em>added this session</em> is not this check's
 * job (specification &sect;9.3's new-vs-baseline filter handles that, the
 * same as catalog JG-SEC-008/JG-SUP); this only detects the shape.
 */
final class Sec011WebSecurityWeakened extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SEC-011", "Web security weakened", Category.SECURITY, Severity.MAJOR);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (name.equals("disable") && isNamedCallScope(call, "csrf")) {
                out.add(finding(ctx, call, "csrf().disable() turns off CSRF protection.",
                        "Keep CSRF protection enabled, or scope the exemption narrowly with ignoringRequestMatchers(...)."));
            } else if (name.equals("csrf") && call.getArguments().size() == 1 && disablesCsrf(call.getArgument(0))) {
                out.add(finding(ctx, call, "CSRF protection is disabled via csrf(...).",
                        "Keep CSRF protection enabled, or scope the exemption narrowly with ignoringRequestMatchers(...)."));
            } else if (name.equals("permitAll") && isNamedCallScope(call, "anyRequest")) {
                out.add(finding(ctx, call, "anyRequest().permitAll() allows every request without authentication.",
                        "Require authentication by default; permit only the specific endpoints that must be public."));
            } else if (name.equals("permitAll") && isRequestMatchersAll(call.getScope().orElse(null))) {
                out.add(finding(ctx, call, "requestMatchers(\"/**\").permitAll() allows every request without authentication.",
                        "Require authentication by default; permit only the specific endpoints that must be public."));
            }
        }
        return out;
    }

    private boolean isNamedCallScope(MethodCallExpr call, String scopeMethodName) {
        return call.getScope().filter(Expression::isMethodCallExpr)
                .map(s -> s.asMethodCallExpr().getNameAsString().equals(scopeMethodName))
                .orElse(false);
    }

    private boolean isRequestMatchersAll(Expression scope) {
        if (scope == null || !scope.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr call = scope.asMethodCallExpr();
        return call.getNameAsString().equals("requestMatchers") && call.getArguments().stream()
                .anyMatch(a -> a.isStringLiteralExpr() && a.asStringLiteralExpr().asString().equals("/**"));
    }

    /** {@code AbstractHttpConfigurer::disable} or a lambda whose body calls {@code .disable()} on its parameter. */
    private boolean disablesCsrf(Expression arg) {
        if (arg.isMethodReferenceExpr()) {
            return arg.asMethodReferenceExpr().getIdentifier().equals("disable");
        }
        if (arg.isLambdaExpr()) {
            LambdaExpr lambda = arg.asLambdaExpr();
            if (lambda.getParameters().size() != 1) {
                return false;
            }
            String param = lambda.getParameters().get(0).getNameAsString();
            Expression body = lambda.getExpressionBody().orElse(singleStatementExpression(lambda));
            return body != null && body.isMethodCallExpr() && body.asMethodCallExpr().getNameAsString().equals("disable")
                    && body.asMethodCallExpr().getScope()
                            .filter(s -> s.isNameExpr() && s.asNameExpr().getNameAsString().equals(param)).isPresent();
        }
        return false;
    }

    private Expression singleStatementExpression(LambdaExpr lambda) {
        if (!lambda.getBody().isBlockStmt()) {
            return null;
        }
        List<Statement> statements = lambda.getBody().asBlockStmt().getStatements();
        if (statements.size() != 1 || !statements.get(0).isExpressionStmt()) {
            return null;
        }
        return statements.get(0).asExpressionStmt().getExpression();
    }
}
