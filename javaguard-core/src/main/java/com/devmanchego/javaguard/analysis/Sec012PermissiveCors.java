package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-SEC-012 (catalog &sect;1.2): a wildcard CORS origin combined with
 * credentials allowed, in the same method (the fluent configuration methods)
 * or the same {@code @CrossOrigin} annotation.
 */
final class Sec012PermissiveCors extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SEC-012", "Permissive CORS with credentials", Category.SECURITY, Severity.CRITICAL);

    private static final Set<String> WILDCARD_ORIGIN_METHODS = Set.of("allowedOrigins", "addAllowedOrigin");
    private static final Set<String> CREDENTIALS_METHODS = Set.of("allowCredentials", "setAllowCredentials");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodDeclaration method : ctx.unit().findAll(MethodDeclaration.class)) {
            boolean wildcard = method.findAll(MethodCallExpr.class).stream()
                    .filter(c -> WILDCARD_ORIGIN_METHODS.contains(c.getNameAsString()))
                    .anyMatch(c -> c.getArguments().stream().anyMatch(this::isWildcard));
            boolean credentials = method.findAll(MethodCallExpr.class).stream()
                    .filter(c -> CREDENTIALS_METHODS.contains(c.getNameAsString()))
                    .anyMatch(c -> !c.getArguments().isEmpty() && isTrue(c.getArgument(0)));
            if (wildcard && credentials) {
                out.add(finding(ctx, method, "This method configures a wildcard CORS origin together with credentials allowed.",
                        "List explicit allowed origins instead of '*' whenever credentials are allowed."));
            }
        }
        for (AnnotationExpr a : ctx.unit().findAll(AnnotationExpr.class)) {
            if (a.getNameAsString().equals("CrossOrigin") && a.isNormalAnnotationExpr()) {
                boolean wildcard = memberValue(a, "origins").filter(v -> v.contains("\"*\"")).isPresent();
                boolean credentials = memberValue(a, "allowCredentials").filter(v -> v.contains("true")).isPresent();
                if (wildcard && credentials) {
                    out.add(finding(ctx, a, "@CrossOrigin combines a wildcard origin with allowCredentials = true.",
                            "List explicit allowed origins instead of '*' whenever credentials are allowed."));
                }
            }
        }
        return out;
    }

    private boolean isWildcard(Expression e) {
        return e.isStringLiteralExpr() && e.asStringLiteralExpr().asString().equals("*");
    }

    private boolean isTrue(Expression e) {
        return e instanceof BooleanLiteralExpr b && b.getValue();
    }

    private java.util.Optional<String> memberValue(AnnotationExpr a, String member) {
        return a.asNormalAnnotationExpr().getPairs().stream()
                .filter(p -> p.getNameAsString().equals(member))
                .map(MemberValuePair::getValue)
                .map(Object::toString)
                .findFirst();
    }
}
