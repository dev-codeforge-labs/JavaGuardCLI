package com.devmanchego.javaguard.analysis;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared heuristic for injection-style rules (catalog JG-SEC-001, -002, -013):
 * is this expression text built from a non-constant piece, whether directly
 * or through a local variable assigned exactly once in the same method
 * (catalog wording: "passed directly or through a local variable assigned
 * once")? Ambiguous cases (zero or several candidate assignments) are treated
 * as safe, favoring precision over recall.
 */
final class DynamicStringBuilds {

    private DynamicStringBuilds() {
    }

    static boolean isDynamic(Expression e) {
        if (AstSupport.isConstantExpression(e)) {
            return false;
        }
        if (e.isEnclosedExpr()) {
            return isDynamic(e.asEnclosedExpr().getInner());
        }
        if (e.isBinaryExpr() && e.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            return true; // not all-constant, or isConstantExpression above would have returned true
        }
        if (e.isMethodCallExpr()) {
            return isDynamicCall(e.asMethodCallExpr());
        }
        if (e.isNameExpr()) {
            return resolveSingleAssignment(e.asNameExpr()).map(DynamicStringBuilds::isDynamic).orElse(false);
        }
        return false;
    }

    private static boolean isDynamicCall(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (name.equals("concat") && !call.getArguments().isEmpty()) {
            return true;
        }
        if ((name.equals("format") || name.equals("formatted"))) {
            return call.getArguments().stream().anyMatch(a -> !AstSupport.isConstantExpression(a));
        }
        if (name.equals("toString")) {
            return call.getScope().filter(Expression::isNameExpr)
                    .map(s -> hasNonConstantAppend(s.asNameExpr())).orElse(false);
        }
        return false;
    }

    /** Parameter types that cannot carry an injection payload: concatenating them is untidy, not exploitable. */
    private static final Set<String> HARMLESS_TYPES = Set.of("byte", "short", "int", "long", "float", "double", "boolean", "char",
            "Byte", "Short", "Integer", "Long", "Float", "Double", "Boolean", "Character", "BigDecimal", "BigInteger", "UUID",
            "LocalDate", "LocalDateTime", "LocalTime", "OffsetDateTime", "ZonedDateTime", "Instant", "Duration");
    private static final Set<String> PASS_THROUGH = Set.of("format", "formatted", "concat", "valueOf", "join", "replace",
            "replaceAll", "trim", "strip", "toLowerCase", "toUpperCase", "append", "toString");
    private static final int MAX_DEPTH = 4;
    /** A non-getter whose own name says it returns SQL ({@code paginationSql()}, {@code orderByClause()}): internal SQL, not data. */
    private static final Pattern SQL_FRAGMENT_NAME = Pattern.compile("(?i)^(?!get|is|has)\\w*(sql|query|clause|schema|fragment)\\w*$");

    /**
     * Does this text carry a string the caller of the enclosing method supplied? True when a non-constant piece is a
     * (String-like) method parameter, a call chain that starts at one ({@code request.getParameter("n")}), or a local
     * assigned once from such a thing. A fragment from a field, a helper call or a numeric parameter is not: it cannot be
     * told from internal SQL ({@code mapper.schema()}, {@code getTableName()}, {@code sqlGenerator.in(...)}) without
     * data flow, so it is only a hint. Used to choose between BLOCKER and MAJOR (measured on fineract, Phase 27).
     */
    static boolean carriesCallerData(Expression e) {
        Optional<MethodDeclaration> method = AstSupport.enclosingMethod(e);
        if (method.isEmpty()) {
            return false;
        }
        Set<String> params = new HashSet<>();
        List<com.github.javaparser.ast.body.Parameter> declared = method.get().getParameters();
        for (int i = 0; i < declared.size(); i++) {
            var p = declared.get(i);
            if (!HARMLESS_TYPES.contains(p.getType().asString()) && !onlyConstantCallers(method.get(), i)) {
                params.add(p.getNameAsString());
            }
        }
        return !params.isEmpty() && carries(e, params, 0);
    }

    /**
     * A private method that is called in its own class, always with a constant at this position, receives no outside
     * data through it ({@code hasUsage("m_loan", code)}). Any other call shape (method reference, other arity) says no.
     */
    private static boolean onlyConstantCallers(MethodDeclaration method, int index) {
        if (!method.hasModifier(Modifier.Keyword.PRIVATE)) {
            return false;
        }
        Optional<com.github.javaparser.ast.body.ClassOrInterfaceDeclaration> owner = AstSupport.enclosingClass(method);
        if (owner.isEmpty() || method.getParameters().get(index).isVarArgs()) {
            return false;
        }
        String name = method.getNameAsString();
        int arity = method.getParameters().size();
        boolean any = false;
        for (MethodCallExpr call : owner.get().findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals(name)) {
                continue;
            }
            boolean own = call.getScope().isEmpty() || call.getScope().get().isThisExpr();
            if (!own || call.getArguments().size() != arity || !AstSupport.isConstantExpression(call.getArgument(index))) {
                return false;
            }
            any = true;
        }
        return any;
    }

    private static boolean carries(Expression e, Set<String> params, int depth) {
        if (depth > MAX_DEPTH || AstSupport.isConstantExpression(e)) {
            return false;
        }
        if (e.isEnclosedExpr()) {
            return carries(e.asEnclosedExpr().getInner(), params, depth);
        }
        if (e.isCastExpr()) {
            return carries(e.asCastExpr().getExpression(), params, depth);
        }
        if (e.isConditionalExpr()) {
            return carries(e.asConditionalExpr().getThenExpr(), params, depth)
                    || carries(e.asConditionalExpr().getElseExpr(), params, depth);
        }
        if (e.isBinaryExpr()) {
            BinaryExpr b = e.asBinaryExpr();
            return b.getOperator() == BinaryExpr.Operator.PLUS
                    && (carries(b.getLeft(), params, depth) || carries(b.getRight(), params, depth));
        }
        if (e.isNameExpr()) {
            String name = e.asNameExpr().getNameAsString();
            return params.contains(name) || resolveSingleAssignment(e.asNameExpr()).map(v -> carries(v, params, depth + 1)).orElse(false);
        }
        if (e.isFieldAccessExpr()) {
            FieldAccessExpr access = e.asFieldAccessExpr();
            return carries(access.getScope(), params, depth + 1);
        }
        if (e.isMethodCallExpr()) {
            MethodCallExpr call = e.asMethodCallExpr();
            if (call.getArguments().isEmpty() && SQL_FRAGMENT_NAME.matcher(call.getNameAsString()).matches()) {
                return false;
            }
            if (call.getScope().filter(scope -> carries(scope, params, depth + 1)).isPresent()) {
                return true;
            }
            if (call.getNameAsString().equals("toString") && call.getScope().filter(Expression::isNameExpr).isPresent()) {
                return builderAppendsCarry(call.getScope().get().asNameExpr(), params, depth);
            }
            return PASS_THROUGH.contains(call.getNameAsString())
                    && call.getArguments().stream().anyMatch(a -> carries(a, params, depth + 1));
        }
        return false;
    }

    private static boolean builderAppendsCarry(NameExpr builderVar, Set<String> params, int depth) {
        String name = builderVar.getNameAsString();
        return AstSupport.enclosingMethod(builderVar).stream()
                .flatMap(m -> m.findAll(MethodCallExpr.class).stream())
                .filter(c -> c.getNameAsString().equals("append") && c.getArguments().size() == 1)
                .filter(c -> appendsTo(c, name))
                .anyMatch(c -> carries(c.getArgument(0), params, depth + 1));
    }

    /** {@code sb.append(<non-constant>)} anywhere on the same variable in the enclosing method. */
    private static boolean hasNonConstantAppend(NameExpr builderVar) {
        String name = builderVar.getNameAsString();
        return AstSupport.enclosingMethod(builderVar).stream()
                .flatMap(m -> m.findAll(MethodCallExpr.class).stream())
                .filter(c -> c.getNameAsString().equals("append"))
                .filter(c -> appendsTo(c, name))
                .anyMatch(c -> !c.getArguments().isEmpty() && !AstSupport.isConstantExpression(c.getArgument(0)));
    }

    /** {@code sb.append(x)} or a chain {@code sb.append(a).append(x)} whose first receiver is the variable {@code name}. */
    private static boolean appendsTo(MethodCallExpr append, String name) {
        Optional<Expression> scope = append.getScope();
        while (scope.isPresent() && scope.get().isMethodCallExpr() && scope.get().asMethodCallExpr().getNameAsString().equals("append")) {
            scope = scope.get().asMethodCallExpr().getScope();
        }
        return scope.filter(s -> s.isNameExpr() && s.asNameExpr().getNameAsString().equals(name)).isPresent();
    }

    private static Optional<Expression> resolveSingleAssignment(NameExpr nameExpr) {
        Optional<MethodDeclaration> method = AstSupport.enclosingMethod(nameExpr);
        if (method.isEmpty()) {
            return Optional.empty();
        }
        String name = nameExpr.getNameAsString();
        List<Expression> candidates = new ArrayList<>();
        for (VariableDeclarator v : method.get().findAll(VariableDeclarator.class)) {
            if (v.getNameAsString().equals(name)) {
                v.getInitializer().ifPresent(candidates::add);
            }
        }
        for (AssignExpr a : method.get().findAll(AssignExpr.class)) {
            if (a.getTarget().isNameExpr() && a.getTarget().asNameExpr().getNameAsString().equals(name)) {
                candidates.add(a.getValue());
            }
        }
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    /**
     * Broader than {@link #isDynamic}: true for anything that is not a
     * constant and cannot be resolved (through at most one local-variable
     * assignment) to one. Unlike {@link #isDynamic}, this does not require a
     * recognizable "building" pattern (concatenation, {@code format}, ...):
     * catalog rules that say only "non-constant" (JG-SEC-002), rather than
     * "built with + / concat / format" (JG-SEC-001, JG-SEC-013), use this.
     */
    static boolean isNonConstant(Expression e) {
        return !isEffectivelyConstant(e);
    }

    private static boolean isEffectivelyConstant(Expression e) {
        if (AstSupport.isConstantExpression(e)) {
            return true;
        }
        if (e.isEnclosedExpr()) {
            return isEffectivelyConstant(e.asEnclosedExpr().getInner());
        }
        if (e.isNameExpr()) {
            return resolveSingleAssignment(e.asNameExpr()).map(DynamicStringBuilds::isEffectivelyConstant).orElse(false);
        }
        return false;
    }

    static Optional<String> receiverTypeOf(MethodCallExpr call) {
        return call.getScope()
                .filter(Expression::isNameExpr)
                .flatMap(s -> AstSupport.declaredTypeSimpleName(s.asNameExpr()));
    }
}
