package com.devmanchego.javaguard.analysis;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Shared vocabulary of the Spring / JPA rules (catalog &sect;1.6). */
final class SpringSupport {

    /** Annotations Spring applies through a proxy (JG-SPR-001). */
    static final Set<String> PROXY_ANNOTATIONS = Set.of("Transactional", "Async", "Cacheable", "CacheEvict", "Retryable");

    /** Subset whose self-invocation is a classic trap (JG-SPR-002). */
    static final Set<String> SELF_INVOCATION_ANNOTATIONS = Set.of("Transactional", "Async", "Cacheable", "Retryable");

    private SpringSupport() {
    }

    /** The first of {@code wanted} present on {@code node}, by simple name. */
    static String proxyAnnotation(NodeWithAnnotations<?> node, Set<String> wanted) {
        for (AnnotationExpr annotation : node.getAnnotations()) {
            String name = AstSupport.simpleName(annotation.getNameAsString());
            if (wanted.contains(name)) {
                return name;
            }
        }
        return null;
    }

    /** Names of the wanted annotations present on {@code node}. */
    static Set<String> annotationsOf(NodeWithAnnotations<?> node, Set<String> wanted) {
        Set<String> found = new java.util.LinkedHashSet<>();
        for (AnnotationExpr annotation : node.getAnnotations()) {
            String name = AstSupport.simpleName(annotation.getNameAsString());
            if (wanted.contains(name)) {
                found.add(name);
            }
        }
        return found;
    }

    /** The class declaration directly enclosing {@code node}, if any. */
    static ClassOrInterfaceDeclaration enclosingType(Node node) {
        return node.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
    }

    /**
     * Simple names of a type and of every type argument, outermost first: {@code ResponseEntity<List<Item>>}
     * gives {@code ResponseEntity, List, Item}.
     */
    static List<String> typeNames(Type type) {
        List<String> names = new ArrayList<>();
        collect(type, names);
        return names;
    }

    private static void collect(Type type, List<String> names) {
        if (type.isClassOrInterfaceType()) {
            ClassOrInterfaceType c = type.asClassOrInterfaceType();
            names.add(c.getNameAsString());
            c.getTypeArguments().ifPresent(args -> args.forEach(a -> collect(a, names)));
        } else if (type.isArrayType()) {
            collect(type.asArrayType().getComponentType(), names);
        } else {
            names.add(type.asString());
        }
    }
}
