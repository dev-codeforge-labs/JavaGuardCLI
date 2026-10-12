package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * JG-REL-019 (catalog &sect;1.4): an SLF4J/Log4j 2 call whose literal message has a number of {@code {}} placeholders that
 * differs from its arguments. One extra trailing argument is accepted unless it is certainly not a throwable (see
 * {@link #looksThrowable}), because SLF4J and Log4j print a trailing exception as a stack trace.
 * Fewer arguments print a literal {@code {}}; more are silently dropped.
 */
final class Rel019LogPlaceholdersDoNotMatch extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-REL-019", "Log placeholders do not match", Category.RELIABILITY, Severity.MAJOR);

    private static final Pattern THROWABLE_NAME = Pattern.compile("(?i)(e|ex|exc|err|error|t|th|throwable|cause|exception|.*exception|.*error)");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (!LogSupport.isLogCall(call) || !call.getArgument(0).isStringLiteralExpr()) {
                continue; // A marker first, a supplier or a variable: the message is not a visible literal.
            }
            String message = call.getArgument(0).asStringLiteralExpr().asString();
            if (message.contains("\\{}")) {
                continue;
            }
            int placeholders = count(message);
            List<Expression> arguments = call.getArguments().subList(1, call.getArguments().size());
            if (arguments.stream().anyMatch(a -> a.isArrayCreationExpr() || a.isArrayInitializerExpr())) {
                continue;
            }
            int given = arguments.size();
            boolean trailingThrowable = given > 0 && looksThrowable(call, arguments.get(given - 1));
            boolean matches = given == placeholders || trailingThrowable && given - 1 == placeholders;
            if (!matches && !(placeholders == 0 && given == 0)) {
                out.add(finding(ctx, call, "The message has " + placeholders + " {} placeholder(s) but the call passes " + given
                                + " argument(s)" + (given > placeholders ? ": the extra ones are dropped." : ": a literal {} is printed."),
                        "Make the number of {} equal to the number of arguments (the exception goes last, without a placeholder)."));
            }
        }
        return out;
    }

    private static int count(String message) {
        int n = 0;
        for (int i = message.indexOf("{}"); i >= 0; i = message.indexOf("{}", i + 2)) {
            n++;
        }
        return n;
    }

    /**
     * Whether the extra trailing argument may be the exception SLF4J/Log4j print as a stack trace. Without type resolution
     * only what is certainly not a throwable counts as not one: a literal, a string concatenation, or a variable declared in
     * the file with a type that is not an exception. A catch parameter always is one.
     */
    private static boolean looksThrowable(MethodCallExpr call, Expression last) {
        if (last.isLiteralExpr() || last.isBinaryExpr() && ((com.github.javaparser.ast.expr.BinaryExpr) last).getOperator()
                == com.github.javaparser.ast.expr.BinaryExpr.Operator.PLUS) {
            return false;
        }
        if (last.isNameExpr()) {
            String name = last.asNameExpr().getNameAsString();
            boolean catchParameter = call.findAncestor(com.github.javaparser.ast.stmt.CatchClause.class)
                    .filter(c -> c.getParameter().getNameAsString().equals(name)).isPresent();
            if (catchParameter || THROWABLE_NAME.matcher(name).matches()) {
                return true;
            }
            return AstSupport.declaredTypeSimpleName(last.asNameExpr())
                    .map(type -> type.matches(".*(Exception|Error|Throwable)")).orElse(true);
        }
        if (last.isObjectCreationExpr()) {
            return last.asObjectCreationExpr().getTypeAsString().matches(".*(Exception|Error|Throwable)");
        }
        return true; // A method call or field access may return the exception: not judged.
    }
}
