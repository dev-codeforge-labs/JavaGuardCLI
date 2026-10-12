package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MemberValuePair;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * JG-SPR-016 (catalog &sect;1.6): a JPQL query - {@code @Query} without {@code nativeQuery = true}, {@code @NamedQuery}, or a
 * literal passed to {@code createQuery} - that names an attribute the entity does not have, or an entity by the wrong name.
 * Spring Data validates {@code @Query} and named queries when the application starts; {@code createQuery} fails at the
 * first call. Only string literals (and literal concatenations, text blocks) are read; see {@link JpqlChecker} for what is
 * and is not judged.
 */
final class Spr016JpqlOnUnknownEntityOrAttribute extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SPR-016", "JPQL on an unknown entity or attribute", Category.SPRING_JPA, Severity.CRITICAL);

    private static final Set<Framework> SPRING_OR_JPA;

    static {
        EnumSet<Framework> frameworks = EnumSet.noneOf(Framework.class);
        frameworks.addAll(Frameworks.SPRING);
        frameworks.addAll(Frameworks.JPA);
        SPRING_OR_JPA = frameworks;
    }

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public Set<Framework> requiresAnyOf() {
        return SPRING_OR_JPA;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Node> sites = new ArrayList<>();
        List<String> queries = new ArrayList<>();
        for (AnnotationExpr annotation : ctx.unit().findAll(AnnotationExpr.class)) {
            String name = AstSupport.simpleName(annotation.getNameAsString());
            if (name.equals("Query") && !isNative(annotation)) {
                member(annotation, "value").flatMap(RuleStrings::literal).ifPresent(q -> {
                    sites.add(annotation);
                    queries.add(q);
                });
            } else if (name.equals("NamedQuery")) {
                member(annotation, "query").flatMap(RuleStrings::literal).ifPresent(q -> {
                    sites.add(annotation);
                    queries.add(q);
                });
            }
        }
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (call.getNameAsString().equals("createQuery") && !call.getArguments().isEmpty() && call.getScope().isPresent()) {
                RuleStrings.literal(call.getArgument(0)).ifPresent(q -> {
                    sites.add(call);
                    queries.add(q);
                });
            }
        }
        if (queries.isEmpty()) {
            return List.of();
        }
        ModuleModel model = ctx.model();
        Set<String> types = new java.util.HashSet<>();
        ctx.unit().findAll(com.github.javaparser.ast.type.ClassOrInterfaceType.class).forEach(t -> types.add(t.getNameAsString()));
        ctx.unit().getImports().forEach(i -> types.add(AstSupport.simpleName(i.getNameAsString())));
        List<Finding> out = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            int index = i;
            JpqlChecker.check(queries.get(i), model, types).ifPresent(problem -> out.add(finding(ctx, sites.get(index),
                    "This JPQL query is invalid: " + problem + ". The query fails when it is created (at start-up for @Query and named queries).",
                    "Use the entity name and attribute names exactly as mapped, or check the query against the entity class.")));
        }
        return out;
    }

    private static boolean isNative(AnnotationExpr annotation) {
        return member(annotation, "nativeQuery").map(v -> v.toString().equals("true")).orElse(false);
    }

    private static Optional<Expression> member(AnnotationExpr annotation, String name) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            return name.equals("value") ? Optional.of(annotation.asSingleMemberAnnotationExpr().getMemberValue()) : Optional.empty();
        }
        if (annotation.isNormalAnnotationExpr()) {
            return annotation.asNormalAnnotationExpr().getPairs().stream().filter(p -> p.getNameAsString().equals(name))
                    .map(MemberValuePair::getValue).findFirst();
        }
        return Optional.empty();
    }

    /** String literals as the compiler would join them. */
    static final class RuleStrings {
        private RuleStrings() {
        }

        static Optional<String> literal(Expression e) {
            if (e.isStringLiteralExpr()) {
                return Optional.of(e.asStringLiteralExpr().asString());
            }
            if (e.isTextBlockLiteralExpr()) {
                return Optional.of(e.asTextBlockLiteralExpr().asString());
            }
            if (e.isEnclosedExpr()) {
                return literal(e.asEnclosedExpr().getInner());
            }
            if (e.isBinaryExpr() && e.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
                Optional<String> left = literal(e.asBinaryExpr().getLeft());
                Optional<String> right = literal(e.asBinaryExpr().getRight());
                return left.isPresent() && right.isPresent() ? Optional.of(left.get() + right.get()) : Optional.empty();
            }
            return Optional.empty();
        }
    }
}
