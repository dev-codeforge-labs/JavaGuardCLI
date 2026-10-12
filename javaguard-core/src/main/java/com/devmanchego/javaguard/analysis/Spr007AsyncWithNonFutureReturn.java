package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-SPR-007 (catalog &sect;1.6): an {@code @Async} method returning anything other than {@code void},
 * {@code Future}, {@code CompletableFuture} or {@code ListenableFuture}. The caller gets {@code null} (or a
 * default) immediately and the real result is thrown away.
 */
final class Spr007AsyncWithNonFutureReturn extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SPR-007", "@Async with non-future return", Category.SPRING_JPA, Severity.MAJOR);

    private static final Set<String> ALLOWED = Set.of("void", "Future", "CompletableFuture", "ListenableFuture");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public Set<Framework> requiresAnyOf() {
        return Frameworks.SPRING;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodDeclaration method : ctx.unit().findAll(MethodDeclaration.class)) {
            if (AstSupport.hasAnnotation(method, "Async")
                    && !ALLOWED.contains(AstSupport.simpleName(method.getTypeAsString()))) {
                out.add(finding(ctx, method, "@Async method " + method.getNameAsString() + "() returns "
                        + method.getTypeAsString() + ", so its caller never sees the result.",
                        "Return CompletableFuture<" + method.getTypeAsString() + "> (or void if nothing is returned)."));
            }
        }
        return out;
    }
}
