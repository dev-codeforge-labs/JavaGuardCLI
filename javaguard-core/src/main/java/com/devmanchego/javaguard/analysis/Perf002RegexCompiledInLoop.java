package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * JG-PERF-002 (catalog &sect;1.3): a regex recompiled on every loop iteration
 * - either directly ({@code Pattern.compile}) or via a {@code String} method
 * that compiles one internally ({@code matches}, {@code replaceAll},
 * {@code replaceFirst}, {@code split}) with a non-trivial pattern.
 */
final class Perf002RegexCompiledInLoop extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-PERF-002", "Regex compiled in loop", Category.PERFORMANCE, Severity.MAJOR);

    private static final Set<String> STRING_REGEX_METHODS = Set.of("matches", "replaceAll", "replaceFirst", "split");
    private static final Pattern METACHARACTER = Pattern.compile("[.^$*+?()\\[\\]{}|\\\\]");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (!AstSupport.isInLoop(call)) {
                continue;
            }
            String name = call.getNameAsString();
            if (name.equals("compile") && isNamedScope(call, "Pattern")) {
                out.add(finding(ctx, call, "Pattern.compile() runs on every iteration.",
                        "Compile the Pattern once before the loop and reuse it."));
            } else if (STRING_REGEX_METHODS.contains(name) && expectedArgCount(name) == call.getArguments().size()
                    && isNonTrivialRegex(call.getArgument(0))) {
                out.add(finding(ctx, call, "String." + name + "() recompiles its regex on every iteration.",
                        "Compile a Pattern once before the loop and reuse its Matcher, instead of a String regex method."));
            }
        }
        return out;
    }

    private int expectedArgCount(String method) {
        return (method.equals("matches") || method.equals("split")) ? 1 : 2;
    }

    private boolean isNonTrivialRegex(Expression arg) {
        if (!arg.isStringLiteralExpr()) {
            return true; // cannot rule it out without knowing the value; err on the side of flagging
        }
        String regex = arg.asStringLiteralExpr().asString();
        return regex.length() > 1 || METACHARACTER.matcher(regex).find();
    }

    private boolean isNamedScope(MethodCallExpr call, String name) {
        return call.getScope().filter(s -> s.isNameExpr() && s.asNameExpr().getNameAsString().equals(name)).isPresent();
    }
}
