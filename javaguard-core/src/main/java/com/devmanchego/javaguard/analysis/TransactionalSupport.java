package com.devmanchego.javaguard.analysis;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code @Transactional} that applies to a method (Spring's or JTA's): the method's own annotation, else its class's.
 * Like Spring's attribute source, a method-level annotation replaces the class-level one entirely.
 */
final class TransactionalSupport {

    private TransactionalSupport() {
    }

    static Optional<AnnotationExpr> effective(MethodDeclaration method) {
        Optional<AnnotationExpr> own = method.getAnnotationByName("Transactional");
        if (own.isPresent()) {
            return own;
        }
        ClassOrInterfaceDeclaration type = SpringSupport.enclosingType(method);
        return type == null ? Optional.empty() : type.getAnnotationByName("Transactional");
    }

    /** {@code NOT_SUPPORTED}, {@code NEVER} (Spring) or {@code TxType.NOT_SUPPORTED}/{@code NEVER} (JTA): no transaction. */
    static boolean suspends(AnnotationExpr transactional) {
        return member(transactional, "propagation").or(() -> member(transactional, "value"))
                .map(Expression::toString).filter(v -> v.endsWith("NOT_SUPPORTED") || v.endsWith("NEVER")).isPresent();
    }

    static boolean readOnly(AnnotationExpr transactional) {
        return member(transactional, "readOnly").map(v -> v.toString().equals("true")).orElse(false);
    }

    /** Simple names given to {@code rollbackFor}, {@code rollbackForClassName} (Spring) and {@code rollbackOn} (JTA). */
    static Set<String> rollbackTypes(AnnotationExpr transactional) {
        Set<String> names = new LinkedHashSet<>();
        for (String attribute : new String[] {"rollbackFor", "rollbackForClassName", "rollbackOn"}) {
            member(transactional, attribute).ifPresent(v -> collect(v, names));
        }
        return names;
    }

    private static void collect(Expression value, Set<String> names) {
        if (value.isArrayInitializerExpr()) {
            value.asArrayInitializerExpr().getValues().forEach(v -> collect(v, names));
        } else if (value.isClassExpr()) {
            names.add(AstSupport.simpleName(value.asClassExpr().getType().asString()));
        } else if (value.isStringLiteralExpr()) {
            names.add(AstSupport.simpleName(value.asStringLiteralExpr().asString()));
        } else {
            names.add("?"); // A constant or an expression: unknown, treated as covering everything.
        }
    }

    static Optional<Expression> member(AnnotationExpr annotation, String name) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            return name.equals("value") ? Optional.of(annotation.asSingleMemberAnnotationExpr().getMemberValue()) : Optional.empty();
        }
        if (annotation.isNormalAnnotationExpr()) {
            return annotation.asNormalAnnotationExpr().getPairs().stream().filter(p -> p.getNameAsString().equals(name))
                    .map(MemberValuePair::getValue).findFirst();
        }
        return Optional.empty();
    }
}
