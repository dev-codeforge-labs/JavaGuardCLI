package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * JG-REL-016 (catalog &sect;1.4): {@code map.get(key)} on a variable declared as a {@code Map} that is assigned to, returned
 * as, or used in arithmetic with a primitive. A missing key gives {@code null}, and unboxing it throws
 * {@code NullPointerException} - only with data that lacks the key. Not reported when the method checks
 * {@code containsKey} on the same map.
 */
final class Rel016UnboxingPossiblyAbsentMapValue extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-REL-016", "Unboxing a possibly absent map value", Category.RELIABILITY, Severity.INFO);

    private static final Set<String> PRIMITIVES = Set.of("int", "long", "double", "float", "short", "byte", "boolean", "char");
    private static final Set<BinaryExpr.Operator> ARITHMETIC = Set.of(BinaryExpr.Operator.MINUS, BinaryExpr.Operator.MULTIPLY,
            BinaryExpr.Operator.DIVIDE, BinaryExpr.Operator.REMAINDER, BinaryExpr.Operator.LESS, BinaryExpr.Operator.GREATER,
            BinaryExpr.Operator.LESS_EQUALS, BinaryExpr.Operator.GREATER_EQUALS);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (!isMapGet(call) || guarded(call) || !unboxed(call)) {
                continue;
            }
            out.add(finding(ctx, call, call + " returns null for an absent key, and it is unboxed here: NullPointerException with data lacking the key.",
                    "Use getOrDefault(key, fallback), or check containsKey / the null result first."));
        }
        return out;
    }

    private static boolean isMapGet(MethodCallExpr call) {
        return call.getNameAsString().equals("get") && call.getArguments().size() == 1
                && call.getScope().flatMap(ResSupport::receiverType).filter(t -> t.endsWith("Map")).isPresent();
    }

    private static boolean guarded(MethodCallExpr get) {
        Optional<String> map = get.getScope().flatMap(ResSupport::variableName);
        return AstSupport.enclosingMethod(get).filter(m -> m.findAll(MethodCallExpr.class).stream()
                .anyMatch(c -> c.getNameAsString().equals("containsKey")
                        && c.getScope().flatMap(ResSupport::variableName).equals(map))).isPresent();
    }

    private static boolean unboxed(MethodCallExpr get) {
        Expression expression = get;
        while (expression.getParentNode().filter(p -> p instanceof com.github.javaparser.ast.expr.EnclosedExpr).isPresent()) {
            expression = (Expression) expression.getParentNode().get();
        }
        var parent = expression.getParentNode().orElse(null);
        if (parent instanceof VariableDeclarator v) {
            return v.getInitializer().filter(i -> i == get).isPresent() && PRIMITIVES.contains(v.getTypeAsString());
        }
        if (parent instanceof ReturnStmt) {
            return AstSupport.enclosingMethod(get).map(MethodDeclaration::getTypeAsString).filter(PRIMITIVES::contains).isPresent();
        }
        if (parent instanceof BinaryExpr b) {
            Expression other = b.getLeft() == expression ? b.getRight() : b.getLeft();
            return ARITHMETIC.contains(b.getOperator())
                    || b.getOperator() == BinaryExpr.Operator.PLUS && (other.isIntegerLiteralExpr() || other.isLongLiteralExpr()
                    || other.isDoubleLiteralExpr());
        }
        return false;
    }
}
