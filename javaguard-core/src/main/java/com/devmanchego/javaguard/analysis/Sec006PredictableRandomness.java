package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * JG-SEC-006 (catalog &sect;1.2): {@code new Random()} / {@code Math.random()}
 * / {@code ThreadLocalRandom} feeding a variable or field whose name suggests
 * a secret. "Feeding" is read here as: is the direct initializer of, or is
 * assigned to, a matching name.
 */
final class Sec006PredictableRandomness extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SEC-006", "Predictable randomness for secrets", Category.SECURITY, Severity.MAJOR);

    private static final Pattern SECRET_NAME = Pattern.compile(
            "(?i).*(token|password|secret|salt|nonce|otp|session|key).*");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (VariableDeclarator v : ctx.unit().findAll(VariableDeclarator.class)) {
            if (v.getInitializer().isPresent() && SECRET_NAME.matcher(v.getNameAsString()).matches()
                    && isRandomSource(v.getInitializer().get())) {
                out.add(finding(ctx, v, "A predictable random source feeds '" + v.getNameAsString() + "'.",
                        "Use java.security.SecureRandom for anything that must not be guessable."));
            }
        }
        for (AssignExpr a : ctx.unit().findAll(AssignExpr.class)) {
            String targetName = targetName(a);
            if (targetName != null && SECRET_NAME.matcher(targetName).matches() && isRandomSource(a.getValue())) {
                out.add(finding(ctx, a, "A predictable random source feeds '" + targetName + "'.",
                        "Use java.security.SecureRandom for anything that must not be guessable."));
            }
        }
        return out;
    }

    private String targetName(AssignExpr a) {
        if (a.getTarget().isNameExpr()) {
            return a.getTarget().asNameExpr().getNameAsString();
        }
        if (a.getTarget().isFieldAccessExpr()) {
            return a.getTarget().asFieldAccessExpr().getNameAsString();
        }
        return null;
    }

    /**
     * True when a {@code new Random()}/{@code Math.random()}/
     * {@code ThreadLocalRandom} source appears anywhere in {@code e} - not
     * only as its top-level shape. The catalog example is exactly this:
     * {@code "T" + new Random().nextInt(...)} nests the random source inside
     * a concatenation and a chained call.
     */
    private boolean isRandomSource(Expression e) {
        if (e.isObjectCreationExpr() && e.asObjectCreationExpr().getType().getNameAsString().equals("Random")) {
            return true;
        }
        if (e.isEnclosedExpr()) {
            return isRandomSource(e.asEnclosedExpr().getInner());
        }
        if (e.isBinaryExpr()) {
            BinaryExpr b = e.asBinaryExpr();
            return isRandomSource(b.getLeft()) || isRandomSource(b.getRight());
        }
        if (!e.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr m = e.asMethodCallExpr();
        if (m.getNameAsString().equals("random") && isNamedScope(m, "Math")) {
            return true;
        }
        if (isThreadLocalRandomCurrent(m)) {
            return true;
        }
        // A chained call on a random source, e.g. new Random().nextInt(...): the
        // scope itself (not the call's own name) is where the source lives.
        return m.getScope().map(this::isRandomSource).orElse(false);
    }

    private boolean isThreadLocalRandomCurrent(MethodCallExpr call) {
        return call.getNameAsString().equals("current") && isNamedScope(call, "ThreadLocalRandom");
    }

    private boolean isNamedScope(MethodCallExpr call, String name) {
        return call.getScope().filter(s -> s.isNameExpr() && s.asNameExpr().getNameAsString().equals(name)).isPresent();
    }
}
