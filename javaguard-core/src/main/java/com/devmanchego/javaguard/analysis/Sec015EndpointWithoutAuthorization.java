package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-SEC-015 (catalog &sect;1.2): a handler method without {@code @PreAuthorize}/{@code @PostAuthorize}/{@code @Secured}/
 * {@code @RolesAllowed}/{@code @PermitAll}/{@code @DenyAll} in a controller where every other handler declares one (at least
 * two of them) and the class itself declares none. The odd one out is usually an endpoint added without thinking about who
 * may call it. Like every file-local rule only what the session added is reported, so an existing unannotated handler is
 * not blamed; {@code @PermitAll} counts as a decision.
 */
final class Sec015EndpointWithoutAuthorization extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SEC-015", "Endpoint without authorization where its siblings have it",
            Category.SECURITY, Severity.MAJOR);

    private static final String[] SECURITY = {"PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed", "PermitAll", "DenyAll"};
    private static final String[] MAPPINGS = {"GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping", "RequestMapping"};

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
        for (ClassOrInterfaceDeclaration type : ctx.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (!AstSupport.hasAnnotation(type, "RestController", "Controller") || AstSupport.hasAnnotation(type, SECURITY)) {
                continue;
            }
            List<MethodDeclaration> handlers = type.getMethods().stream().filter(m -> AstSupport.hasAnnotation(m, MAPPINGS)).toList();
            List<MethodDeclaration> open = handlers.stream().filter(m -> !AstSupport.hasAnnotation(m, SECURITY)).toList();
            if (open.size() != 1 || handlers.size() < 3) {
                continue;
            }
            MethodDeclaration handler = open.get(0);
            out.add(finding(ctx, handler, handler.getNameAsString() + "() is the only handler of " + type.getNameAsString()
                            + " without an authorization annotation, while its " + (handlers.size() - 1) + " siblings declare one.",
                    "Add the authorization this endpoint needs (@PreAuthorize(...)), or @PermitAll if it is meant to be public."));
        }
        return out;
    }
}
