package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.IfStmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-CONC-005 (catalog &sect;1.5): {@code if (!map.containsKey(k)) map.put(k, v)}
 * (or the {@code get(k) == null} equivalent) on a {@code ConcurrentMap} is a
 * check-then-act race - another thread can insert between the check and the
 * put. {@code computeIfAbsent}/{@code putIfAbsent} are atomic and race-free.
 */
final class Conc005CheckThenActOnConcurrentMap extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-CONC-005", "Check-then-act on concurrent map", Category.CONCURRENCY, Severity.MAJOR);

    private static final Set<String> CONCURRENT_MAP_TYPES = Set.of(
            "ConcurrentMap", "ConcurrentHashMap", "ConcurrentSkipListMap");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (IfStmt ifStmt : ctx.unit().findAll(IfStmt.class)) {
            CheckedKey checked = checkedAbsence(ifStmt.getCondition());
            if (checked == null || !isConcurrentMap(checked.mapRef())) {
                continue;
            }
            for (MethodCallExpr call : ifStmt.getThenStmt().findAll(MethodCallExpr.class)) {
                if (!call.getNameAsString().equals("put") || call.getArguments().size() < 2) {
                    continue;
                }
                if (!sameReceiver(call, checked.mapRef().getNameAsString())) {
                    continue;
                }
                if (!sameKey(call.getArgument(0), checked.keyExpr())) {
                    continue;
                }
                out.add(finding(ctx, ifStmt,
                        "'" + checked.mapRef().getNameAsString() + "' is checked and then put on the same key without atomicity.",
                        "Use computeIfAbsent(key, k -> value) or putIfAbsent(key, value) instead."));
            }
        }
        return out;
    }

    private record CheckedKey(NameExpr mapRef, Expression keyExpr) {
    }

    private CheckedKey checkedAbsence(Expression condition) {
        if (condition instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            if (unary.getExpression() instanceof MethodCallExpr call && call.getNameAsString().equals("containsKey")
                    && call.getScope().filter(Expression::isNameExpr).isPresent() && !call.getArguments().isEmpty()) {
                return new CheckedKey(call.getScope().get().asNameExpr(), call.getArgument(0));
            }
            return null;
        }
        if (condition instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.EQUALS && b.getRight().isNullLiteralExpr()
                && b.getLeft() instanceof MethodCallExpr call && call.getNameAsString().equals("get")
                && call.getScope().filter(Expression::isNameExpr).isPresent() && !call.getArguments().isEmpty()) {
            return new CheckedKey(call.getScope().get().asNameExpr(), call.getArgument(0));
        }
        return null;
    }

    private boolean sameReceiver(MethodCallExpr call, String mapName) {
        return call.getScope().filter(Expression::isNameExpr)
                .map(s -> s.asNameExpr().getNameAsString().equals(mapName)).orElse(false);
    }

    private boolean sameKey(Expression a, Expression b) {
        if (a instanceof NameExpr an && b instanceof NameExpr bn) {
            return an.getNameAsString().equals(bn.getNameAsString());
        }
        return a.toString().equals(b.toString());
    }

    private boolean isConcurrentMap(NameExpr mapRef) {
        return AstSupport.declaredTypeSimpleName(mapRef).filter(CONCURRENT_MAP_TYPES::contains).isPresent();
    }
}
