package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-REL-001 (catalog &sect;1.4): a {@code catch} block that hides the
 * failure - either empty, or (for a broad exception type) doing nothing that
 * counts as acknowledging it.
 */
final class Rel001ExceptionSwallowed extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-REL-001", "Exception swallowed", Category.RELIABILITY, Severity.MAJOR);

    private static final Set<String> BROAD_TYPES = Set.of("Exception", "Throwable", "RuntimeException");
    private static final Set<String> LOG_METHODS = Set.of("trace", "debug", "info", "warn", "error", "fatal");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (CatchClause cc : ctx.unit().findAll(CatchClause.class)) {
            boolean broad = catchesBroadType(cc);
            if (cc.getBody().getStatements().isEmpty()) {
                out.add(findingWithSeverity(ctx, cc, broad ? Severity.CRITICAL : Severity.MAJOR,
                        "This catch block is empty; the exception is silently discarded.",
                        "Handle the error (rethrow, log, or return/assign a fallback); never ignore it silently."));
            } else if (broad && !acknowledgesException(cc)) {
                out.add(findingWithSeverity(ctx, cc, Severity.CRITICAL,
                        "A broad exception is caught but neither rethrown, logged, nor used to produce a result.",
                        "Rethrow, log with the exception attached, or return/assign an explicit fallback value."));
            }
        }
        return out;
    }

    private boolean catchesBroadType(CatchClause cc) {
        return typeNames(cc.getParameter().getType()).stream().anyMatch(BROAD_TYPES::contains);
    }

    private List<String> typeNames(Type t) {
        if (t.isUnionType()) {
            return t.asUnionType().getElements().stream().map(e -> AstSupport.simpleName(e.asString())).toList();
        }
        return List.of(AstSupport.simpleName(t.asString()));
    }

    private boolean acknowledgesException(CatchClause cc) {
        if (!cc.getBody().findAll(ThrowStmt.class).isEmpty()) {
            return true;
        }
        if (!cc.getBody().findAll(ReturnStmt.class).isEmpty()) {
            return true;
        }
        if (!cc.getBody().findAll(AssignExpr.class).isEmpty()) {
            return true;
        }
        for (MethodCallExpr call : cc.getBody().findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (name.equals("printStackTrace") || LOG_METHODS.contains(name)) {
                return true;
            }
        }
        return false;
    }
}
