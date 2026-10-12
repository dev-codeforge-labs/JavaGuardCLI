package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JG-SEC-001 (catalog &sect;1.2): SQL text built by concatenation and passed
 * to a JDBC/JPA/Hibernate/Spring-JDBC execution method. Gated on the
 * receiver's declared type (not the method name alone, which is far too
 * generic on its own: {@code execute}, {@code query}, {@code update}).
 *
 * <p>Severity varies per occurrence (Phase 27): BLOCKER when the text carries a string that a method parameter supplied,
 * MAJOR (advice) when it is built of pieces whose origin the syntax cannot show.
 */
final class Sec001SqlConcatenation extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SEC-001", "SQL built by concatenation", Category.SECURITY, Severity.BLOCKER);

    private static final Map<String, Set<String>> SINKS = Map.ofEntries(
            Map.entry("Statement", Set.of("executeQuery", "executeUpdate", "execute")),
            Map.entry("PreparedStatement", Set.of("executeQuery", "executeUpdate", "execute")),
            Map.entry("CallableStatement", Set.of("executeQuery", "executeUpdate", "execute")),
            Map.entry("Connection", Set.of("prepareStatement", "prepareCall")),
            Map.entry("EntityManager", Set.of("createQuery", "createNativeQuery")),
            Map.entry("Session", Set.of("createQuery", "createNativeQuery", "createSQLQuery")),
            Map.entry("JdbcTemplate", jdbcTemplateMethods()),
            Map.entry("NamedParameterJdbcTemplate", jdbcTemplateMethods()));

    private static Set<String> jdbcTemplateMethods() {
        return Set.of("query", "queryForObject", "queryForList", "queryForMap", "queryForRowSet", "update",
                "execute", "batchUpdate");
    }

    /**
     * The receiver's declared type, or {@code Statement} for the call chain {@code connection.createStatement().executeQuery(...)}:
     * the most common way to write the vulnerable form never names the statement in a variable.
     */
    private static String receiverType(MethodCallExpr call) {
        Optional<String> declared = DynamicStringBuilds.receiverTypeOf(call);
        if (declared.isPresent()) {
            return declared.get();
        }
        return call.getScope().filter(Expression::isMethodCallExpr)
                .filter(scope -> scope.asMethodCallExpr().getNameAsString().equals("createStatement")).isPresent() ? "Statement" : null;
    }

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (call.getArguments().isEmpty()) {
                continue;
            }
            String type = receiverType(call);
            Set<String> methods = type == null ? null : SINKS.get(type);
            if (methods == null || !methods.contains(call.getNameAsString())) {
                continue;
            }
            Expression sqlArg = call.getArgument(0);
            if (!DynamicStringBuilds.isDynamic(sqlArg)) {
                continue;
            }
            String target = type + "." + call.getNameAsString();
            if (DynamicStringBuilds.carriesCallerData(sqlArg)) {
                out.add(finding(ctx, call, "SQL text is built dynamically and passed to " + target + ".",
                        "Use a parameterized query ('?' placeholders, or a named-parameter API) instead of "
                                + "concatenating values into SQL text."));
            } else {
                // Fragments of unknown origin (a mapper's schema(), a table name, a generated IN list) are what fineract
                // builds everywhere with bound parameters: advise, never block (Phase 27 measurement).
                out.add(findingWithSeverity(ctx, call, Severity.MAJOR,
                        "SQL text is built by concatenation of pieces whose origin is not visible, and passed to " + target + ".",
                        "If any piece comes from a request, user input or a caller, bind it with a '?' placeholder; fragments that "
                                + "are fixed in the code (column lists, table names from constants) are fine."));
            }
        }
        return out;
    }
}
