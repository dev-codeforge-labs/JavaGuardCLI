package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-REL-014 (catalog &sect;1.4): a {@code public static} field is shared,
 * unsynchronized, mutable state reachable from anywhere - either because it
 * is not {@code final} at all, or because it is a {@code final} reference to
 * an array or a plain mutable collection (the reference cannot be reassigned,
 * but its contents can).
 */
final class Rel014MutableStaticStateExposed extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-REL-014", "Mutable static state exposed", Category.RELIABILITY, Severity.MAJOR);

    private static final Set<String> MUTABLE_COLLECTION_TYPES = Set.of(
            "ArrayList", "LinkedList", "HashMap", "LinkedHashMap", "TreeMap", "HashSet", "LinkedHashSet", "TreeSet");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (FieldDeclaration f : ctx.unit().findAll(FieldDeclaration.class)) {
            if (!f.hasModifier(Modifier.Keyword.PUBLIC) || !f.hasModifier(Modifier.Keyword.STATIC)) {
                continue;
            }
            boolean isFinal = f.hasModifier(Modifier.Keyword.FINAL);
            for (VariableDeclarator v : f.getVariables()) {
                if (!isFinal) {
                    out.add(finding(ctx, v, "'" + v.getNameAsString() + "' is a public static field that is not final.",
                            "Make it private with controlled access, or at least final; a public mutable static is shared, unsynchronized state."));
                } else if (v.getType().isArrayType() || v.getInitializer().filter(this::isMutableCollection).isPresent()) {
                    out.add(finding(ctx, v, "'" + v.getNameAsString() + "' is a final public static array/collection: the reference cannot change, but its contents can.",
                            "Expose an unmodifiable view (List.copyOf, Collections.unmodifiableList) or a defensive copy instead."));
                }
            }
        }
        return out;
    }

    private boolean isMutableCollection(Expression init) {
        return init.isObjectCreationExpr() && MUTABLE_COLLECTION_TYPES.contains(init.asObjectCreationExpr().getType().getNameAsString());
    }
}
