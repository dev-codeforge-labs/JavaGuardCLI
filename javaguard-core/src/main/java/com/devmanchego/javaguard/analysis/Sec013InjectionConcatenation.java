package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JG-SEC-013 (catalog &sect;1.2): a non-constant, concatenated string passed
 * to an LDAP filter, XPath, Spring EL, or script-engine evaluation sink.
 * Reuses {@link DynamicStringBuilds#isDynamic}, the same "built by
 * concatenation" semantics as JG-SEC-001 (the catalog uses the same wording).
 */
final class Sec013InjectionConcatenation extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SEC-013", "LDAP/XPath/EL built by concatenation", Category.SECURITY, Severity.MAJOR);

    /** receiver type -> (method name -> index of the argument that carries the expression/filter text). */
    private static final Map<String, Map<String, Integer>> SINKS = Map.of(
            "DirContext", Map.of("search", 1),
            "XPath", Map.of("compile", 0, "evaluate", 0),
            "ExpressionParser", Map.of("parseExpression", 0),
            "ScriptEngine", Map.of("eval", 0));

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            String type = DynamicStringBuilds.receiverTypeOf(call).orElse(null);
            Map<String, Integer> methods = type == null ? null : SINKS.get(type);
            Integer argIndex = methods == null ? null : methods.get(call.getNameAsString());
            if (argIndex == null || call.getArguments().size() <= argIndex) {
                continue;
            }
            if (DynamicStringBuilds.isDynamic(call.getArgument(argIndex))) {
                out.add(finding(ctx, call, "A " + type + "." + call.getNameAsString()
                        + "() expression is built by concatenation.",
                        REMEDIATIONS.get(type)));
            }
        }
        return out;
    }

    /** Keep the expression text constant and pass the value separately, in each API's own way. */
    private static final Map<String, String> REMEDIATIONS = Map.of(
            "DirContext", "Keep the filter constant and pass the values as arguments: search(name, \"(uid={0})\", "
                    + "new Object[] {value}, controls).",
            "XPath", "Keep the expression constant with a variable ($name) and supply the value through "
                    + "xpath.setXPathVariableResolver(...).",
            "ExpressionParser", "Keep the expression constant with a variable (#name) and set the value on the "
                    + "EvaluationContext (context.setVariable(\"name\", value)); for untrusted input use a SimpleEvaluationContext.",
            "ScriptEngine", "Keep the script constant and pass the values as bindings: engine.eval(script, bindings) "
                    + "with bindings.put(\"name\", value).");
}
