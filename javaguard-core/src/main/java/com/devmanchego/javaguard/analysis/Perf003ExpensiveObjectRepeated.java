package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-PERF-003 (catalog &sect;1.3): an expensive object created on every loop
 * iteration, or on every call of a method belonging to a Spring/Jakarta
 * singleton bean (approximating "called per request": a singleton's methods
 * run repeatedly for the life of the application, unlike a one-off script).
 * A few construction shapes ({@code SimpleDateFormat}, {@code DateTimeFormatter.ofPattern},
 * {@code DocumentBuilderFactory.newInstance}) are flagged only inside a loop,
 * matching the catalog exactly.
 */
final class Perf003ExpensiveObjectRepeated extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-PERF-003", "Expensive object created repeatedly", Category.PERFORMANCE, Severity.MAJOR);

    private static final Set<String> ALWAYS_CONTEXT_TYPES = Set.of("ObjectMapper", "XmlMapper", "Gson", "RestTemplate");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (ObjectCreationExpr creation : ctx.unit().findAll(ObjectCreationExpr.class)) {
            String type = creation.getType().getNameAsString();
            if (type.equals("SimpleDateFormat") && AstSupport.isInLoop(creation)) {
                out.add(expensiveFinding(ctx, creation, type));
            } else if (ALWAYS_CONTEXT_TYPES.contains(type) && repeatedContext(creation)) {
                out.add(expensiveFinding(ctx, creation, type));
            }
        }
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (name.equals("ofPattern") && isNamedScope(call, "DateTimeFormatter") && AstSupport.isInLoop(call)) {
                out.add(expensiveFinding(ctx, call, "DateTimeFormatter.ofPattern(...)"));
            } else if (name.equals("newInstance") && isNamedScope(call, "DocumentBuilderFactory") && AstSupport.isInLoop(call)) {
                out.add(expensiveFinding(ctx, call, "DocumentBuilderFactory.newInstance()"));
            } else if (name.equals("newInstance") && isNamedScope(call, "JAXBContext") && repeatedContext(call)) {
                out.add(expensiveFinding(ctx, call, "JAXBContext.newInstance(...)"));
            } else if (name.equals("create") && isNamedScope(call, "WebClient") && repeatedContext(call)) {
                out.add(expensiveFinding(ctx, call, "WebClient.create(...)"));
            } else if (name.equals("newHttpClient") && isNamedScope(call, "HttpClient") && repeatedContext(call)) {
                out.add(expensiveFinding(ctx, call, "HttpClient.newHttpClient()"));
            } else if (name.equals("build") && (isChainRoot(call, "WebClient", "builder") || isChainRoot(call, "HttpClient", "newBuilder"))
                    && repeatedContext(call)) {
                out.add(expensiveFinding(ctx, call, call.toString()));
            }
        }
        return out;
    }

    private Finding expensiveFinding(AstCheckContext ctx, com.github.javaparser.ast.Node at, String what) {
        return finding(ctx, at, what + " is constructed repeatedly instead of once.",
                "Create it once (a field, or before the loop) and reuse the instance; it is safe to share and expensive to build.");
    }

    private boolean repeatedContext(com.github.javaparser.ast.Node n) {
        if (AstSupport.isInLoop(n)) {
            return true;
        }
        // A singleton bean's field initializer or constructor runs once, at bean creation - not "repeatedly".
        // Only creation inside a regular method (called on every request/use) qualifies.
        return AstSupport.enclosingMethod(n).isPresent()
                && AstSupport.enclosingClass(n).filter(AstSupport::isSingletonBean).isPresent();
    }

    private boolean isChainRoot(MethodCallExpr call, String type, String method) {
        return call.getScope().filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(s -> s.getNameAsString().equals(method) && isNamedScope(s, type))
                .isPresent();
    }

    private boolean isNamedScope(MethodCallExpr call, String name) {
        return call.getScope().filter(s -> s.isNameExpr() && s.asNameExpr().getNameAsString().equals(name)).isPresent();
    }
}
