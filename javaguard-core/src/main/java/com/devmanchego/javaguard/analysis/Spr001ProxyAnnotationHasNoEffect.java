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
 * JG-SPR-001 (catalog &sect;1.6): {@code @Transactional}, {@code @Async}, {@code @Cacheable},
 * {@code @CacheEvict} or {@code @Retryable} on a {@code private}, {@code final} or {@code static} method.
 * Spring applies these through a proxy that overrides the method, which none of those can be: the annotation is
 * silently ignored and the method runs without the transaction, the async hand-off, the cache or the retry.
 */
final class Spr001ProxyAnnotationHasNoEffect extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SPR-001", "Proxy annotation has no effect", Category.SPRING_JPA,
            Severity.CRITICAL);

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
            String proxyAnnotation = SpringSupport.proxyAnnotation(method, SpringSupport.PROXY_ANNOTATIONS);
            if (proxyAnnotation == null) {
                continue;
            }
            String why = method.isPrivate() ? "private" : method.isFinal() ? "final" : method.isStatic() ? "static" : null;
            if (why != null) {
                out.add(finding(ctx, method, "@" + proxyAnnotation + " on the " + why + " method " + method.getNameAsString()
                        + "() is ignored: Spring cannot proxy it.",
                        "Make the method public, non-final and non-static and call it from another bean, or move it to a "
                                + "separate @Service bean that the callers inject."));
            }
        }
        return out;
    }
}
