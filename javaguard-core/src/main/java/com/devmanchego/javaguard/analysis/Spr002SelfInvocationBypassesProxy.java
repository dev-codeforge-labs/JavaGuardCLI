package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ThisExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-SPR-002 (catalog &sect;1.6): a method calls, unqualified or through {@code this.}, another method of the same
 * class that is annotated {@code @Transactional}/{@code @Async}/{@code @Cacheable}/{@code @Retryable}, and the
 * caller does not carry the same annotation (nor, for {@code @Transactional}, does the class). The call never
 * leaves the object, so it never passes through the Spring proxy and the annotation does nothing.
 */
final class Spr002SelfInvocationBypassesProxy extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SPR-002", "Self-invocation bypasses proxy", Category.SPRING_JPA,
            Severity.MAJOR);

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
            boolean classTransactional = AstSupport.hasAnnotation(type, "Transactional");
            for (MethodDeclaration caller : type.getMethods()) {
                Set<String> callerAnnotations = SpringSupport.annotationsOf(caller, SpringSupport.SELF_INVOCATION_ANNOTATIONS);
                for (MethodCallExpr call : caller.findAll(MethodCallExpr.class)) {
                    if (!isCallOnThis(call)) {
                        continue;
                    }
                    for (MethodDeclaration target : type.getMethodsByName(call.getNameAsString())) {
                        if (target == caller || target.getParameters().size() != call.getArguments().size()) {
                            continue;
                        }
                        for (String annotation : SpringSupport.annotationsOf(target, SpringSupport.SELF_INVOCATION_ANNOTATIONS)) {
                            boolean covered = callerAnnotations.contains(annotation)
                                    || (annotation.equals("Transactional") && classTransactional);
                            if (!covered) {
                                out.add(finding(ctx, call, caller.getNameAsString() + "() calls " + call.getNameAsString()
                                        + "() on the same object, so its @" + annotation + " is bypassed.",
                                        "Call it through another bean (or an injected self-reference), or move it to a "
                                                + "separate component."));
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private static boolean isCallOnThis(MethodCallExpr call) {
        return call.getScope().isEmpty() || call.getScope().get() instanceof ThisExpr;
    }
}
