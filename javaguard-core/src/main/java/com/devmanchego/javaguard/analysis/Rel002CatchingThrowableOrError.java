package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-REL-002 (catalog &sect;1.4): catching {@code Throwable}/{@code Error} (or
 * a subtype) without rethrowing swallows JVM-level failures the program
 * cannot safely continue past (e.g. {@code OutOfMemoryError}).
 */
final class Rel002CatchingThrowableOrError extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-REL-002", "Catching Throwable/Error", Category.RELIABILITY, Severity.MAJOR);

    private static final Set<String> TARGET_TYPES = Set.of(
            "Throwable", "Error", "OutOfMemoryError", "StackOverflowError", "VirtualMachineError", "LinkageError");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (CatchClause cc : ctx.unit().findAll(CatchClause.class)) {
            if (matchesTarget(cc.getParameter().getType()) && cc.getBody().findAll(ThrowStmt.class).isEmpty()) {
                out.add(finding(ctx, cc, "'" + cc.getParameter().getType() + "' is caught without rethrowing.",
                        "Catch a narrower type, or rethrow after handling (JVM-level errors usually mean the program cannot safely continue)."));
            }
        }
        return out;
    }

    private boolean matchesTarget(Type t) {
        if (t.isUnionType()) {
            return t.asUnionType().getElements().stream().anyMatch(e -> TARGET_TYPES.contains(AstSupport.simpleName(e.asString())));
        }
        return TARGET_TYPES.contains(AstSupport.simpleName(t.asString()));
    }
}
