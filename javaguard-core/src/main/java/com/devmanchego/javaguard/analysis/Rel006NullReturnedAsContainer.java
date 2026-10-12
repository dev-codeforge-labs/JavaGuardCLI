package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-REL-006 (catalog &sect;1.4): {@code return null;} from a method declared
 * to return {@code Optional}/a collection/{@code Stream} - callers that
 * reasonably assume "never null" for these types get an NPE instead.
 */
final class Rel006NullReturnedAsContainer extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-REL-006", "Null returned as Optional/collection", Category.RELIABILITY, Severity.CRITICAL);

    private static final Set<String> CONTAINER_TYPES = Set.of("Optional", "List", "Set", "Map", "Collection", "Stream");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodDeclaration method : ctx.unit().findAll(MethodDeclaration.class)) {
            if (!CONTAINER_TYPES.contains(baseType(method.getTypeAsString())) || method.getBody().isEmpty()) {
                continue;
            }
            for (ReturnStmt r : method.getBody().get().findAll(ReturnStmt.class)) {
                if (directlyIn(r, method) && r.getExpression().filter(e -> e.isNullLiteralExpr()).isPresent()) {
                    out.add(finding(ctx, r, "This method returns " + method.getTypeAsString() + " but returns null here.",
                            "Return Optional.empty()/an empty collection/Stream.empty() instead of null."));
                }
            }
        }
        return out;
    }

    private String baseType(String typeAsString) {
        return AstSupport.simpleName(typeAsString.contains("<") ? typeAsString.substring(0, typeAsString.indexOf('<')) : typeAsString);
    }

    /** True unless a nested method/lambda/anonymous class sits between {@code r} and {@code method}. */
    private boolean directlyIn(ReturnStmt r, MethodDeclaration method) {
        Node p = r.getParentNode().orElse(null);
        while (p != null && p != method) {
            if (p instanceof LambdaExpr || p instanceof MethodDeclaration || p instanceof ObjectCreationExpr) {
                return false;
            }
            p = p.getParentNode().orElse(null);
        }
        return p == method;
    }
}
