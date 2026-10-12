package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;

/**
 * JG-REL-015 (catalog &sect;1.4): {@code Collectors.toMap(key, value)} with two arguments. With real data a duplicate key
 * throws {@code IllegalStateException: Duplicate key ...}, which test data rarely reproduces. Maps built once from constant
 * data (static initializers, an enum's {@code values()}) are not reported.
 */
final class Rel015ToMapWithoutMergeFunction extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-REL-015", "toMap without merge function", Category.RELIABILITY, Severity.INFO);

    /**
     * Built once from constant data: a static field or static initializer, or a stream over an enum's {@code values()}. A
     * duplicate key there fails at class loading, which any test shows; the rule is about data that varies.
     */
    private static boolean constantData(MethodCallExpr call) {
        boolean staticInit = call.findAncestor(com.github.javaparser.ast.body.FieldDeclaration.class)
                .filter(com.github.javaparser.ast.body.FieldDeclaration::isStatic).isPresent()
                || call.findAncestor(com.github.javaparser.ast.body.InitializerDeclaration.class)
                .filter(com.github.javaparser.ast.body.InitializerDeclaration::isStatic).isPresent();
        Node scope = call;
        while (scope.getParentNode().isPresent() && !(scope instanceof com.github.javaparser.ast.stmt.Statement)) {
            scope = scope.getParentNode().get();
        }
        boolean enumValues = scope.findAll(MethodCallExpr.class).stream()
                .anyMatch(m -> m.getNameAsString().equals("values") && m.getArguments().isEmpty()
                        && m.getScope().map(Object::toString).filter(t -> !t.matches("[A-Z][\\w.]*")).isEmpty());
        return staticInit || enumValues;
    }

    /**
     * A key unique by construction: an identifier ({@code X::getId}, {@code x -> x.getId()}, a record's {@code id}) or a map
     * entry's key ({@code Map.Entry::getKey}).
     */
    private static boolean uniqueKey(com.github.javaparser.ast.expr.Expression key) {
        String text = key.toString().replace(" ", "");
        return text.matches(".*::(getId|id|getKey|getUuid|uuid)") || text.matches("\\w+->\\w+\\.(getId|id|getKey|getUuid|uuid)\\(\\)");
    }

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            boolean collectors = call.getScope().filter(s -> s.toString().equals("Collectors")
                    || s.toString().endsWith(".Collectors")).isPresent();
            if (collectors && call.getNameAsString().equals("toMap") && call.getArguments().size() == 2 && !constantData(call)
                    && !uniqueKey(call.getArgument(0))) {
                out.add(finding(ctx, call, "Collectors.toMap(key, value) throws IllegalStateException when two elements have the same key.",
                        "Add a merge function as third argument, e.g. (first, second) -> first, or use groupingBy if duplicates are expected."));
            }
        }
        return out;
    }
}
