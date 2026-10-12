package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import com.github.javaparser.ast.body.Parameter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-SPR-014 (catalog &sect;1.6): a {@code @RequestBody} parameter without {@code @Valid}/{@code @Validated} whose type
 * declares Bean Validation constraints in this module. Spring MVC validates a request body only when the parameter asks
 * for it, so the constraints are dead code and invalid input reaches the service. Only the parameter's own type is
 * considered (not a {@code List<T>} element), and only types the module declares. PATCH handlers are not reported: a
 * partial update's body is incomplete on purpose.
 */
final class Spr014RequestBodyNotValidated extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SPR-014", "Request body not validated", Category.SPRING_JPA, Severity.MAJOR);

    /** A PATCH handler: its body is a partial object, which the type's constraints would reject on purpose. */
    private static boolean partialUpdate(Parameter parameter) {
        return parameter.getParentNode().filter(n -> n instanceof com.github.javaparser.ast.body.MethodDeclaration)
                .map(n -> (com.github.javaparser.ast.body.MethodDeclaration) n)
                .filter(m -> AstSupport.hasAnnotation(m, "PatchMapping")
                        || m.getAnnotationByName("RequestMapping").map(a -> a.toString().contains("PATCH")).orElse(false))
                .isPresent();
    }

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
        for (Parameter parameter : ctx.unit().findAll(Parameter.class)) {
            if (!AstSupport.hasAnnotation(parameter, "RequestBody") || AstSupport.hasAnnotation(parameter, "Valid", "Validated")
                    || !parameter.getType().isClassOrInterfaceType()
                    || parameter.getType().asClassOrInterfaceType().getTypeArguments().isPresent()) {
                continue;
            }
            if (partialUpdate(parameter)) {
                continue;
            }
            String type = parameter.getType().asClassOrInterfaceType().getNameAsString();
            if (model == null) {
                model = ctx.model();
            }
            if (model.isValidated(type)) {
                out.add(finding(ctx, parameter, "The request body " + parameter.getNameAsString() + " (" + type
                                + ") has validation constraints, but without @Valid Spring does not check them: invalid input is accepted.",
                        "Annotate the parameter with @Valid (jakarta.validation) or @Validated."));
            }
        }
        return out;
    }
}
