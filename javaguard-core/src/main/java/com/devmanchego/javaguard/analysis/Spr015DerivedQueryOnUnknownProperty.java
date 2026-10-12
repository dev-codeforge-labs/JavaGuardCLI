package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JG-SPR-015 (catalog &sect;1.6): a Spring Data repository method whose derived query names a property the entity does not
 * have, or that declares fewer parameters than its conditions consume. Spring Data rejects both when the application
 * starts (a {@code PropertyReferenceException}, or "expects at least N arguments"), so nothing that uses the repository
 * runs. The entity is the first type argument of the repository's base interface ({@code JpaRepository<Customer, Long>}),
 * its attributes come from the module model; see {@link SpringDataDerivedQuery} for how the name is read. Methods with
 * {@code @Query}, {@code @NativeQuery} or {@code @Procedure}, default methods, and {@code @Override} methods (redeclared from a
 * custom fragment interface whose {@code Impl} class implements them) are not derived queries.
 */
final class Spr015DerivedQueryOnUnknownProperty extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SPR-015", "Derived query on an unknown property", Category.SPRING_JPA, Severity.BLOCKER);

    /** {@code @Override}: the method redeclares one of a custom repository fragment, which its implementation class answers. */
    private static final Set<String> NOT_DERIVED = Set.of("Query", "NativeQuery", "Procedure", "Override");
    private static final Set<String> SPECIAL_PARAMETERS = Set.of("Pageable", "Sort", "Limit", "ScrollPosition", "OffsetScrollPosition",
            "KeysetScrollPosition", "Class");

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
        ModuleModel model = null;
        for (ClassOrInterfaceDeclaration repository : ctx.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            Optional<String> domain = RepositorySupport.domainType(repository);
            if (domain.isEmpty() || !repository.isInterface()) {
                continue;
            }
            if (model == null) {
                model = ctx.model();
            }
            if (!model.isEntity(domain.get())) {
                continue;
            }
            for (MethodDeclaration method : repository.getMethods()) {
                if (method.isDefault() || method.isStatic() || method.getBody().isPresent()
                        || AstSupport.hasAnnotation(method, NOT_DERIVED.toArray(String[]::new))) {
                    continue;
                }
                check(ctx, model, domain.get(), method).ifPresent(out::add);
            }
        }
        return out;
    }

    private Optional<Finding> check(AstCheckContext ctx, ModuleModel model, String entity, MethodDeclaration method) {
        Optional<SpringDataDerivedQuery.Parsed> parsed = SpringDataDerivedQuery.parse(method.getNameAsString());
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        List<String> paths = new ArrayList<>();
        parsed.get().parts().forEach(p -> paths.add(p.property()));
        paths.addAll(parsed.get().orderBy());
        for (String path : paths) {
            if (SpringDataDerivedQuery.resolve(path, entity, model) == SpringDataDerivedQuery.Resolution.MISSING) {
                return Optional.of(finding(ctx, method, method.getNameAsString() + "(...): " + entity + " has no property path '"
                        + SpringDataDerivedQuery.decapitalize(path) + "'" + JpqlChecker.suggestion(entity, SpringDataDerivedQuery.decapitalize(path), model)
                        + ". Spring Data rejects this query method when the repository is created (application start-up).",
                        "Use the entity's attribute names exactly (" + known(model, entity) + "), or write the query with @Query."));
            }
        }
        boolean countable = parsed.get().parts().stream().allMatch(p -> p.arguments() >= 0);
        long arguments = method.getParameters().stream().filter(p -> !special(p)).count();
        int required = parsed.get().requiredArguments();
        if (countable && arguments < required) {
            return Optional.of(finding(ctx, method, method.getNameAsString() + "(...) has " + arguments + " parameter(s) but its conditions need "
                    + required + ". Spring Data rejects this query method when the repository is created (application start-up).",
                    "Add one parameter per condition value, in the order of the conditions (Between takes two, IsNull/True/Empty none)."));
        }
        return Optional.empty();
    }

    private static boolean special(Parameter parameter) {
        if (!parameter.getType().isClassOrInterfaceType()) {
            return false;
        }
        ClassOrInterfaceType type = parameter.getType().asClassOrInterfaceType();
        return SPECIAL_PARAMETERS.contains(type.getNameAsString());
    }

    private static String known(ModuleModel model, String entity) {
        Set<String> names = new java.util.TreeSet<>();
        String current = entity;
        for (int i = 0; i < 10 && current != null; i++) {
            Optional<ModuleModel.Persistent> p = model.persistent(current);
            if (p.isEmpty()) {
                break;
            }
            names.addAll(p.get().attributes().keySet());
            current = p.get().superclass().orElse(null);
        }
        return names.stream().limit(15).collect(Collectors.joining(", "));
    }
}
