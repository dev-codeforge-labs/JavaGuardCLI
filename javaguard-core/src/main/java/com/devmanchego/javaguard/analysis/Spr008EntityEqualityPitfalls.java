package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JG-SPR-008 (catalog &sect;1.6): equality on an {@code @Entity} that breaks with generated ids or lazy
 * associations. Lombok {@code @Data}, or {@code @EqualsAndHashCode} without {@code onlyExplicitlyIncluded}, makes
 * {@code equals}/{@code hashCode} cover every field (lazy collections included); a hand-written
 * {@code equals}/{@code hashCode} that reads only an {@code @GeneratedValue} id, with no null handling, makes all
 * unsaved instances equal and changes the hash when the id is assigned.
 */
final class Spr008EntityEqualityPitfalls extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SPR-008", "Entity equality on generated id / @Data on entity",
            Category.SPRING_JPA, Severity.MAJOR);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public Set<Framework> requiresAnyOf() {
        return Frameworks.JPA;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (ClassOrInterfaceDeclaration entity : ctx.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (!AstSupport.hasAnnotation(entity, "Entity")) {
                continue;
            }
            for (AnnotationExpr annotation : entity.getAnnotations()) {
                String name = AstSupport.simpleName(annotation.getNameAsString());
                if (name.equals("Data")) {
                    out.add(finding(ctx, annotation, "@Data on the entity " + entity.getNameAsString()
                            + " generates equals/hashCode/toString over every field, lazy associations included.",
                            "Use @Getter/@Setter and an explicit equals/hashCode on a business key."));
                } else if (name.equals("EqualsAndHashCode") && !onlyExplicitlyIncluded(annotation)) {
                    out.add(finding(ctx, annotation, "@EqualsAndHashCode on the entity " + entity.getNameAsString()
                            + " covers every field, lazy associations included.",
                            "Set onlyExplicitlyIncluded = true and include only the business key."));
                }
            }
            generatedIdOnlyEquality(ctx, entity, out);
        }
        return out;
    }

    private void generatedIdOnlyEquality(AstCheckContext ctx, ClassOrInterfaceDeclaration entity, List<Finding> out) {
        Set<String> generatedIds = new HashSet<>();
        Set<String> allFields = new HashSet<>();
        for (FieldDeclaration field : entity.getFields()) {
            boolean generated = AstSupport.hasAnnotation(field, "GeneratedValue");
            field.getVariables().forEach(v -> {
                allFields.add(v.getNameAsString());
                if (generated) {
                    generatedIds.add(v.getNameAsString());
                }
            });
        }
        if (generatedIds.isEmpty()) {
            return;
        }
        for (MethodDeclaration method : entity.getMethods()) {
            boolean isEquals = method.getNameAsString().equals("equals") && method.getParameters().size() == 1;
            boolean isHashCode = method.getNameAsString().equals("hashCode") && method.getParameters().isEmpty();
            if (!(isEquals || isHashCode) || method.getBody().isEmpty()) {
                continue;
            }
            Set<String> read = new HashSet<>();
            method.getBody().get().findAll(NameExpr.class).forEach(n -> read.add(n.getNameAsString()));
            method.getBody().get().findAll(FieldAccessExpr.class).forEach(f -> read.add(f.getNameAsString()));
            read.retainAll(allFields);
            boolean handlesNull = !method.getBody().get().findAll(NullLiteralExpr.class).isEmpty();
            if (!read.isEmpty() && generatedIds.containsAll(read) && !handlesNull) {
                out.add(finding(ctx, method, entity.getNameAsString() + "." + method.getNameAsString()
                        + "() uses only the generated id " + read + " without null handling.",
                        "Compare a business key, or treat an unassigned (null) id as 'not equal'."));
            }
        }
    }

    private static boolean onlyExplicitlyIncluded(AnnotationExpr annotation) {
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("onlyExplicitlyIncluded") && pair.getValue().toString().equals("true")) {
                    return true;
                }
            }
        }
        return false;
    }
}
