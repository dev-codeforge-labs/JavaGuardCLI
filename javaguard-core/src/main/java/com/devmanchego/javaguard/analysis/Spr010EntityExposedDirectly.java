package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-SPR-010 (catalog &sect;1.6): a JPA entity bound straight from the request ({@code @RequestBody}) or returned
 * straight from a controller, also when wrapped ({@code ResponseEntity<List<Order>>}). Binding invites mass
 * assignment of fields the client must not set; serializing risks lazy-loading failures and leaking columns.
 * "Entity" means a class annotated {@code @Entity} in this file or anywhere in the module (by simple name).
 */
final class Spr010EntityExposedDirectly extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SPR-010", "Entity exposed/bound directly", Category.SPRING_JPA, Severity.INFO);

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
        for (Parameter parameter : ctx.unit().findAll(Parameter.class)) {
            if (AstSupport.hasAnnotation(parameter, "RequestBody")) {
                SpringSupport.typeNames(parameter.getType()).stream().filter(ctx::isEntity).findFirst()
                        .ifPresent(entity -> out.add(finding(ctx, parameter, "The entity " + entity
                                + " is bound directly from the request body.",
                                "Bind a DTO and map it to the entity, so clients cannot set fields they should not.")));
            }
        }
        for (ClassOrInterfaceDeclaration controller : ctx.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (!AstSupport.hasAnnotation(controller, "RestController", "Controller")) {
                continue;
            }
            for (MethodDeclaration method : controller.getMethods()) {
                SpringSupport.typeNames(method.getType()).stream().filter(ctx::isEntity).findFirst()
                        .ifPresent(entity -> out.add(finding(ctx, method, "The controller method " + method.getNameAsString()
                                + "() returns the entity " + entity + " directly.",
                                "Return a DTO, so lazy associations are not serialized and columns are not leaked.")));
            }
        }
        return out;
    }
}
