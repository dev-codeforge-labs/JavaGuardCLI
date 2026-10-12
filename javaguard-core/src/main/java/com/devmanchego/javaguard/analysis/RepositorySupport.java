package com.devmanchego.javaguard.analysis;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.util.Optional;
import java.util.Set;

/** Spring Data repository facts read from syntax: which interface is a repository, and of which domain type. */
final class RepositorySupport {

    private static final Set<String> BASES = Set.of("Repository", "CrudRepository", "ListCrudRepository", "PagingAndSortingRepository",
            "ListPagingAndSortingRepository", "JpaRepository", "JpaSpecificationExecutor", "QueryByExampleExecutor",
            "RevisionRepository");

    private RepositorySupport() {
    }

    /**
     * The domain type of a repository interface: the first type argument of a Spring Data base interface it extends, or of
     * a custom {@code *Repository} base, or the {@code domainClass} of {@code @RepositoryDefinition}.
     */
    static Optional<String> domainType(ClassOrInterfaceDeclaration type) {
        for (ClassOrInterfaceType extended : type.getExtendedTypes()) {
            String name = extended.getNameAsString();
            if ((BASES.contains(name) || name.endsWith("Repository")) && extended.getTypeArguments().filter(a -> !a.isEmpty()).isPresent()) {
                Type first = extended.getTypeArguments().get().get(0);
                if (first.isClassOrInterfaceType()) {
                    return Optional.of(first.asClassOrInterfaceType().getNameAsString());
                }
            }
        }
        return type.getAnnotationByName("RepositoryDefinition").flatMap(a -> a.isNormalAnnotationExpr()
                ? a.asNormalAnnotationExpr().getPairs().stream().filter(p -> p.getNameAsString().equals("domainClass"))
                .map(p -> p.getValue()).filter(Expression::isClassExpr)
                .map(v -> AstSupport.simpleName(v.asClassExpr().getType().asString())).findFirst()
                : Optional.empty());
    }
}
