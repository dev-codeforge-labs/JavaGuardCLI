package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-SPR-004 (catalog &sect;1.6): {@code @OneToMany}/{@code @ManyToMany} with {@code fetch = FetchType.EAGER}.
 * Every load of the owner then loads the whole collection, and joins of several such collections multiply rows.
 */
final class Spr004EagerToManyAssociation extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SPR-004", "Eager to-many association", Category.SPRING_JPA, Severity.MAJOR);

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
        for (AnnotationExpr annotation : ctx.unit().findAll(AnnotationExpr.class)) {
            String name = AstSupport.simpleName(annotation.getNameAsString());
            if ((name.equals("OneToMany") || name.equals("ManyToMany")) && annotation instanceof NormalAnnotationExpr normal) {
                for (MemberValuePair pair : normal.getPairs()) {
                    if (pair.getNameAsString().equals("fetch") && pair.getValue().toString().endsWith("EAGER")) {
                        out.add(finding(ctx, annotation, "@" + name + " is fetched eagerly: every load of the owner loads the "
                                + "whole collection.", "Use the default lazy fetch and fetch the collection explicitly "
                                + "(join fetch / entity graph) where it is needed."));
                    }
                }
            }
        }
        return out;
    }
}
