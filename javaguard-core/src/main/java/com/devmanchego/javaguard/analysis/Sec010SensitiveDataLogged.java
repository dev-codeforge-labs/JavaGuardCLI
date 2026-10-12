package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * JG-SEC-010 (catalog &sect;1.2): a logger or {@code System.out}/{@code err}
 * call with an argument whose name looks secret-shaped (same name pattern as
 * catalog JG-SEC-008).
 */
final class Sec010SensitiveDataLogged extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SEC-010", "Sensitive data logged", Category.SECURITY, Severity.MAJOR);

    private static final Set<String> LOGGER_RECEIVERS = Set.of("log", "logger", "LOG", "LOGGER");
    private static final Set<String> LOG_METHODS = Set.of("trace", "debug", "info", "warn", "error", "fatal");
    private static final Set<String> PRINT_METHODS = Set.of("print", "println", "printf", "format");
    private static final Pattern SECRET_NAME = Pattern.compile(
            "(?i).*(password|passwd|pwd|secret|api[_-]?key|access[_-]?key|token|private[_-]?key|credentials).*");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (!isLoggingCall(call)) {
                continue;
            }
            for (Expression arg : call.getArguments()) {
                identifierName(arg).filter(n -> SECRET_NAME.matcher(n).matches()).ifPresent(name ->
                        out.add(finding(ctx, call, "'" + name + "' is passed to a logging/print call.",
                                "Do not log secrets; log a redacted placeholder or omit the value entirely.")));
            }
        }
        return out;
    }

    private boolean isLoggingCall(MethodCallExpr call) {
        String name = call.getNameAsString();
        return call.getScope().flatMap(this::identifierName).map(scope -> {
            if (LOGGER_RECEIVERS.contains(scope)) {
                return LOG_METHODS.contains(name);
            }
            // System.out/System.err: the scope is a FieldAccessExpr ("out"/"err" on System), not a bare name.
            if (scope.equals("out") || scope.equals("err")) {
                return PRINT_METHODS.contains(name);
            }
            return false;
        }).orElse(false);
    }

    private Optional<String> identifierName(Expression e) {
        if (e.isNameExpr()) {
            return Optional.of(e.asNameExpr().getNameAsString());
        }
        if (e.isFieldAccessExpr()) {
            return Optional.of(e.asFieldAccessExpr().getNameAsString());
        }
        return Optional.empty();
    }
}
