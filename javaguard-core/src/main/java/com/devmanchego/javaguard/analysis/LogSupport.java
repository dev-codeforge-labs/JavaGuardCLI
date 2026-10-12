package com.devmanchego.javaguard.analysis;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;

import java.util.Set;
import java.util.regex.Pattern;

/** Which calls are logger calls (SLF4J, Log4j 2, commons-logging style), by receiver name or declared type. */
final class LogSupport {

    private static final Set<String> LEVELS = Set.of("trace", "debug", "info", "warn", "error", "fatal");
    private static final Set<String> TYPES = Set.of("Logger", "Log", "ExtendedLogger");
    private static final Pattern NAME = Pattern.compile("(?i)_?(log|logger|logging|logs|slf4j)");

    private LogSupport() {
    }

    static boolean isLogCall(MethodCallExpr call) {
        if (!LEVELS.contains(call.getNameAsString()) || call.getScope().isEmpty() || call.getArguments().isEmpty()) {
            return false;
        }
        Expression scope = call.getScope().get();
        String name = scope instanceof NameExpr n ? n.getNameAsString()
                : scope instanceof FieldAccessExpr f ? f.getNameAsString() : null;
        if (name == null) {
            return false;
        }
        return NAME.matcher(name).matches() || ResSupport.receiverType(scope).filter(TYPES::contains).isPresent();
    }
}
