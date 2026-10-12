package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * JG-SPR-013 (catalog &sect;1.6): a {@code javax.*} Java EE API in a project on the {@code jakarta.*} namespace (Spring Boot 3,
 * Hibernate 6, Jakarta EE 9+; {@link Framework#JAKARTA_EE}). The code compiles when an old API jar is still on the class
 * path, but the runtime only looks for the {@code jakarta.*} types: a {@code javax.persistence.Entity} is not mapped, a
 * {@code javax.validation} constraint is not checked, a {@code javax.servlet.Filter} is not registered. The JSR-250
 * lifecycle annotations ({@code javax.annotation.PostConstruct}/{@code PreDestroy}) are not reported: Spring 6 still
 * honours them.
 */
final class Spr013JavaxApiInJakartaProject extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SPR-013", "javax API in a Jakarta project", Category.SPRING_JPA, Severity.CRITICAL);

    private static final List<String> PACKAGES = List.of("javax.persistence", "javax.validation", "javax.servlet", "javax.ws.rs",
            "javax.transaction.Transactional");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public Set<Framework> requiresAnyOf() {
        return Set.of(Framework.JAKARTA_EE);
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (ImportDeclaration declaration : ctx.unit().getImports()) {
            javax(declaration.getNameAsString()).ifPresent(p -> out.add(report(ctx, declaration, p)));
        }
        for (AnnotationExpr annotation : ctx.unit().findAll(AnnotationExpr.class)) {
            javax(annotation.getNameAsString()).ifPresent(p -> out.add(report(ctx, annotation, p)));
        }
        return out;
    }

    private static Optional<String> javax(String name) {
        return PACKAGES.stream().filter(p -> name.equals(p) || name.startsWith(p + ".")).findFirst();
    }

    private Finding report(AstCheckContext ctx, Node node, String api) {
        String jakarta = "jakarta" + api.substring("javax".length());
        return finding(ctx, node, api + " is the Java EE namespace, but this project is on Jakarta EE 9+ (Spring Boot 3 / Hibernate 6): "
                        + "the runtime ignores it.",
                "Use " + jakarta + " instead, and remove the javax API dependency that lets this compile.");
    }
}
