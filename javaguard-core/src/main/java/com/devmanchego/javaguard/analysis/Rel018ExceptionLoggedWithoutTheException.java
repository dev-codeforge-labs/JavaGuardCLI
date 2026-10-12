package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-REL-018 (catalog &sect;1.4): in a {@code catch (X e)}, a logger call that prints {@code e.getMessage()} or
 * {@code e.toString()} at error level but does not pass {@code e} itself, and the catch does not keep the exception otherwise
 * (rethrow, wrap, hand over). The stack trace - where the failure happened - never reaches the log.
 */
final class Rel018ExceptionLoggedWithoutTheException extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-REL-018", "Exception logged without the exception", Category.RELIABILITY, Severity.MAJOR);

    private static final Set<String> TEXT = Set.of("getMessage", "toString", "getLocalizedMessage");
    /** At warn/info level, logging only the message of an expected condition is usually deliberate. */
    private static final Set<String> ERROR_LEVELS = Set.of("error", "fatal");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (CatchClause catchClause : ctx.unit().findAll(CatchClause.class)) {
            String name = catchClause.getParameter().getNameAsString();
            for (MethodCallExpr call : catchClause.getBody().findAll(MethodCallExpr.class)) {
                if (!LogSupport.isLogCall(call)) {
                    continue;
                }
                boolean passesIt = call.getArguments().stream().anyMatch(a -> a.isNameExpr() && a.asNameExpr().getNameAsString().equals(name));
                boolean usesText = call.getArguments().stream().anyMatch(a -> a.findAll(MethodCallExpr.class).stream()
                        .anyMatch(m -> TEXT.contains(m.getNameAsString())
                                && m.getScope().filter(s -> s.isNameExpr() && s.asNameExpr().getNameAsString().equals(name)).isPresent()));
                if (usesText && !passesIt && ERROR_LEVELS.contains(call.getNameAsString()) && !keptElsewhere(catchClause, name, call)) {
                    out.add(finding(ctx, call, "The log call prints the text of '" + name + "' but not the exception: its stack trace is lost.",
                            "Pass the exception as the last argument (log.error(\"message\", " + name + ")) instead of, or besides, its message."));
                }
            }
        }
        return out;
    }

    /**
     * The exception survives anyway: the catch rethrows it, wraps it, or hands it to another call (collected, reported),
     * so its stack trace is not lost by this log line.
     */
    private static boolean keptElsewhere(CatchClause catchClause, String name, MethodCallExpr log) {
        boolean thrown = catchClause.getBody().findAll(com.github.javaparser.ast.stmt.ThrowStmt.class).stream()
                .anyMatch(t -> t.getExpression().findAll(com.github.javaparser.ast.expr.NameExpr.class).stream()
                        .anyMatch(n -> n.getNameAsString().equals(name)));
        boolean passed = catchClause.getBody().findAll(MethodCallExpr.class).stream().filter(c -> c != log && !c.findAncestor(MethodCallExpr.class)
                        .filter(a -> a == log).isPresent())
                .anyMatch(c -> c.getArguments().stream().anyMatch(a -> mentions(a, name)));
        boolean wrapped = catchClause.getBody().findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class).stream()
                .anyMatch(o -> o.getArguments().stream().anyMatch(a -> refersTo(a, name)));
        return thrown || passed || wrapped;
    }

    private static boolean mentions(Expression e, String name) {
        return refersTo(e, name) || e.findAll(com.github.javaparser.ast.expr.NameExpr.class).stream()
                .anyMatch(n -> n.getNameAsString().equals(name));
    }

    static boolean refersTo(Expression e, String name) {
        return e.isNameExpr() && e.asNameExpr().getNameAsString().equals(name);
    }
}
