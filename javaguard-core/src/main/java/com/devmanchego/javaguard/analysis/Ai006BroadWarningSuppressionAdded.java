package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-AI-006 (catalog &sect;1.8): {@code @SuppressWarnings("all"/"unchecked"/"rawtypes")}
 * at class level silences those warnings for every member, present and
 * future - broad enough to hide a real problem introduced later. A narrower,
 * method- or statement-level suppression with a specific category is safer.
 */
final class Ai006BroadWarningSuppressionAdded extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-AI-006", "Broad warning suppression added", Category.AI_DEBRIS, Severity.INFO);

    private static final Set<String> BROAD_CATEGORIES = Set.of("all", "unchecked", "rawtypes");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (ClassOrInterfaceDeclaration c : ctx.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            for (AnnotationExpr a : c.getAnnotations()) {
                if (!AstSupport.simpleName(a.getNameAsString()).equals("SuppressWarnings")) {
                    continue;
                }
                List<String> categories = suppressedCategories(a);
                if (categories.stream().anyMatch(BROAD_CATEGORIES::contains)) {
                    out.add(finding(ctx, a, "Class '" + c.getNameAsString() + "' broadly suppresses warnings (" + String.join(", ", categories) + ").",
                            "Narrow the suppression to the specific member/statement that needs it, and to a specific warning category."));
                }
            }
        }
        return out;
    }

    private List<String> suppressedCategories(AnnotationExpr a) {
        List<String> values = new ArrayList<>();
        if (a instanceof SingleMemberAnnotationExpr single) {
            collectStringValues(single.getMemberValue(), values);
        } else if (a instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("value")) {
                    collectStringValues(pair.getValue(), values);
                }
            }
        }
        return values;
    }

    private void collectStringValues(Expression e, List<String> out) {
        if (e.isStringLiteralExpr()) {
            out.add(e.asStringLiteralExpr().asString());
        } else if (e instanceof ArrayInitializerExpr array) {
            for (Expression value : array.getValues()) {
                collectStringValues(value, out);
            }
        }
    }
}
