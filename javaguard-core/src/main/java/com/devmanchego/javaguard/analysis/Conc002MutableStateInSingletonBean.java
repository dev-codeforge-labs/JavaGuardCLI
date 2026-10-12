package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-CONC-002 (catalog &sect;1.5): a singleton bean's instance state is shared
 * by every caller. This flags a non-{@code final} field (not populated only by
 * injection or the constructor) reassigned from a non-constructor method, and
 * a {@code final} field of a well-known mutable collection type whose contents
 * are mutated from a non-constructor method.
 */
final class Conc002MutableStateInSingletonBean extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-CONC-002", "Mutable state in singleton bean", Category.CONCURRENCY, Severity.MAJOR);

    private static final Set<String> INJECTION_ANNOTATIONS = Set.of("Autowired", "Inject", "Value", "PersistenceContext");
    private static final Set<String> MUTABLE_COLLECTION_TYPES = Set.of("HashMap", "ArrayList", "HashSet", "LinkedList", "TreeMap");
    private static final Set<String> MUTATING_METHODS = Set.of("put", "putAll", "add", "addAll", "remove", "clear", "putIfAbsent");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (ClassOrInterfaceDeclaration c : ctx.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (!AstSupport.isSingletonBean(c)) {
                continue;
            }
            for (FieldDeclaration f : c.getFields()) {
                if (f.hasModifier(Modifier.Keyword.STATIC) || AstSupport.hasAnnotation(f, INJECTION_ANNOTATIONS.toArray(String[]::new))) {
                    continue;
                }
                boolean isFinal = f.hasModifier(Modifier.Keyword.FINAL);
                String typeName = AstSupport.simpleName(f.getElementType().asString());
                for (VariableDeclarator v : f.getVariables()) {
                    String name = v.getNameAsString();
                    if (!isFinal) {
                        reportIfAssignedOutsideConstructor(ctx, c, name, out);
                    } else if (MUTABLE_COLLECTION_TYPES.contains(typeName)) {
                        reportIfMutatedOutsideConstructor(ctx, c, name, out);
                    }
                }
            }
        }
        return out;
    }

    private void reportIfAssignedOutsideConstructor(AstCheckContext ctx, ClassOrInterfaceDeclaration c, String fieldName, List<Finding> out) {
        for (AssignExpr assign : c.findAll(AssignExpr.class)) {
            if (isInConstructor(assign) || !isFieldTarget(assign.getTarget(), fieldName)) {
                continue;
            }
            out.add(finding(ctx, assign,
                    "'" + fieldName + "' is instance state of a singleton bean, reassigned outside the constructor.",
                    "Make it immutable and constructor-assigned only, or use a concurrency-safe holder (AtomicReference, volatile with synchronized access)."));
        }
    }

    private void reportIfMutatedOutsideConstructor(AstCheckContext ctx, ClassOrInterfaceDeclaration c, String fieldName, List<Finding> out) {
        for (MethodCallExpr call : c.findAll(MethodCallExpr.class)) {
            if (isInConstructor(call) || !MUTATING_METHODS.contains(call.getNameAsString())) {
                continue;
            }
            if (call.getScope().filter(scope -> isFieldTarget(scope, fieldName)).isPresent()) {
                out.add(finding(ctx, call,
                        "'" + fieldName + "' is a mutable collection field of a singleton bean, mutated outside the constructor.",
                        "Use a concurrent collection (ConcurrentHashMap, CopyOnWriteArrayList) or synchronize access."));
            }
        }
    }

    private boolean isInConstructor(com.github.javaparser.ast.Node n) {
        return n.findAncestor(ConstructorDeclaration.class).isPresent();
    }

    private boolean isFieldTarget(Expression e, String fieldName) {
        if (e instanceof NameExpr ne) {
            return ne.getNameAsString().equals(fieldName);
        }
        if (e instanceof FieldAccessExpr fa) {
            return fa.getNameAsString().equals(fieldName) && fa.getScope() instanceof ThisExpr;
        }
        return false;
    }
}
