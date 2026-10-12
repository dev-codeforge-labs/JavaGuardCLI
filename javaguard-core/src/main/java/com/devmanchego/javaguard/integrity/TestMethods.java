package com.devmanchego.javaguard.integrity;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The methods of one source file as the test-integrity rules need them, and the pairing of a baseline
 * file's test methods with the current file's (specification &sect;11.1, catalog &sect;1.7).
 *
 * <p>Pairing is what keeps renames and refactors from looking like deletions. A baseline test is matched,
 * in this order, with the current method of the same type and signature; the only current method of the
 * same type and name (a test converted to {@code @ParameterizedTest} gains parameters); and a current test
 * with an identical, non-empty body anywhere in the file (a pure rename, or a move into a nested class).
 * Whatever is left over is "removed".
 */
final class TestMethods {

    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory",
            "TestTemplate");

    /** One method of a file. */
    record Method(String typePath, String name, String key, MethodDeclaration declaration, boolean test,
                  String body, int statements, Set<String> annotations) {
        int line() {
            return declaration.getBegin().map(p -> p.line).orElse(1);
        }

        String display() {
            return typePath + "." + name;
        }
    }

    /** A baseline test and the current method it became. */
    record Pair(Method baseline, Method current) {
    }

    record Pairing(List<Pair> pairs, List<Method> removed, List<Method> added) {
    }

    private TestMethods() {
    }

    static List<Method> index(CompilationUnit unit) {
        List<Method> methods = new ArrayList<>();
        for (MethodDeclaration declaration : unit.findAll(MethodDeclaration.class)) {
            String typePath = typePath(declaration);
            String signature = declaration.getParameters().stream()
                    .map(p -> stripGenerics(p.getType().asString()))
                    .collect(Collectors.joining(","));
            Set<String> annotations = annotationNames(declaration);
            boolean test = annotations.stream().anyMatch(TEST_ANNOTATIONS::contains);
            int statements = declaration.getBody().map(b -> b.getStatements().size()).orElse(0);
            String body = declaration.getBody().map(Node::toString).orElse("");
            methods.add(new Method(typePath, declaration.getNameAsString(),
                    typePath + "#" + declaration.getNameAsString() + "(" + signature + ")", declaration, test, body,
                    statements, annotations));
        }
        return methods;
    }

    static Pairing pair(List<Method> baseline, List<Method> current) {
        Map<String, Method> currentByKey = new LinkedHashMap<>();
        current.forEach(m -> currentByKey.putIfAbsent(m.key(), m));
        Map<Method, Method> matched = new IdentityHashMap<>();
        Set<Method> used = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        List<Method> baselineTests = baseline.stream().filter(Method::test).toList();
        for (Method b : baselineTests) {
            Method c = currentByKey.get(b.key());
            if (c != null && used.add(c)) {
                matched.put(b, c);
            }
        }
        for (Method b : baselineTests) {
            if (matched.containsKey(b)) {
                continue;
            }
            long sameNameBaseline = baselineTests.stream().filter(o -> !matched.containsKey(o)
                    && o.typePath().equals(b.typePath()) && o.name().equals(b.name())).count();
            List<Method> candidates = current.stream().filter(c -> !used.contains(c) && c.typePath().equals(b.typePath())
                    && c.name().equals(b.name())).toList();
            if (sameNameBaseline == 1 && candidates.size() == 1) {
                used.add(candidates.get(0));
                matched.put(b, candidates.get(0));
            }
        }
        for (Method b : baselineTests) {
            if (matched.containsKey(b) || b.statements() == 0) {
                continue;
            }
            Optional<Method> renamed = current.stream()
                    .filter(c -> c.test() && !used.contains(c) && c.body().equals(b.body())).findFirst();
            renamed.ifPresent(c -> {
                used.add(c);
                matched.put(b, c);
            });
        }

        List<Pair> pairs = new ArrayList<>();
        List<Method> removed = new ArrayList<>();
        for (Method b : baselineTests) {
            Method c = matched.get(b);
            if (c == null) {
                removed.add(b);
            } else {
                pairs.add(new Pair(b, c));
            }
        }
        List<Method> added = current.stream().filter(c -> c.test() && !used.contains(c)).toList();
        return new Pairing(pairs, removed, added);
    }

    /** Methods of the file by simple name: the same-file helpers an assertion count may look through. */
    static Map<String, List<MethodDeclaration>> byName(List<Method> methods) {
        Map<String, List<MethodDeclaration>> byName = new LinkedHashMap<>();
        for (Method m : methods) {
            byName.computeIfAbsent(m.name(), k -> new ArrayList<>()).add(m.declaration());
        }
        return byName;
    }

    /** Simple names of an element's annotations, plus the synthetic {@code Test(enabled=false)} and {@code Test(expected)}. */
    static Set<String> annotationNames(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node) {
        Set<String> names = new LinkedHashSet<>();
        for (AnnotationExpr annotation : node.getAnnotations()) {
            String name = simple(annotation.getNameAsString());
            names.add(name);
            if (name.equals("Test") && annotation instanceof NormalAnnotationExpr normal) {
                for (MemberValuePair pair : normal.getPairs()) {
                    if (pair.getNameAsString().equals("enabled") && pair.getValue() instanceof BooleanLiteralExpr b && !b.getValue()) {
                        names.add("Test(enabled=false)");
                    }
                    if (pair.getNameAsString().equals("expected")) {
                        names.add("Test(expected)");
                    }
                }
            }
        }
        return names;
    }

    static String typePath(Node node) {
        List<String> names = new ArrayList<>();
        for (Optional<Node> parent = node.getParentNode(); parent.isPresent(); parent = parent.get().getParentNode()) {
            if (parent.get() instanceof TypeDeclaration<?> type) {
                names.add(0, type.getNameAsString());
            }
        }
        return String.join(".", names);
    }

    static String simple(String maybeQualified) {
        int dot = maybeQualified.lastIndexOf('.');
        return dot < 0 ? maybeQualified : maybeQualified.substring(dot + 1);
    }

    private static String stripGenerics(String type) {
        int lt = type.indexOf('<');
        return lt < 0 ? type : type.substring(0, lt) + type.substring(type.lastIndexOf('>') + 1);
    }
}
