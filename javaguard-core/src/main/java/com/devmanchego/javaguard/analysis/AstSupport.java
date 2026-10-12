package com.devmanchego.javaguard.analysis;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.Optional;
import java.util.Set;

/**
 * Shared syntactic pattern helpers, used across many {@link AstCheck}
 * implementations. Deliberately heuristic (no symbol resolution): each
 * heuristic's limitation is noted, and rules that rely on one document it in
 * their own Javadoc too.
 */
final class AstSupport {

    private AstSupport() {
    }

    // --- loops and stream pipelines -----------------------------------------

    private static final Set<String> STREAM_ITERATION_METHODS = Set.of(
            "forEach", "forEachOrdered", "map", "filter", "peek", "anyMatch", "allMatch", "noneMatch",
            "mapToInt", "mapToLong", "mapToDouble", "mapToObj", "flatMap");

    /**
     * True when {@code n} is lexically inside a {@code for}/{@code for-each}/
     * {@code while}/{@code do}, or inside a lambda passed to a stream
     * iteration method ({@code forEach}, {@code map}, {@code filter}, ...).
     * Stops at the nearest enclosing method/constructor/initializer/type, so
     * it never leaks into an unrelated outer scope.
     */
    static boolean isInLoop(Node n) {
        Node current = n;
        while (current != null) {
            if (current instanceof ForStmt || current instanceof ForEachStmt
                    || current instanceof WhileStmt || current instanceof DoStmt) {
                return true;
            }
            if (current instanceof LambdaExpr lambda && isStreamIterationLambda(lambda)) {
                return true;
            }
            if (isScopeBoundary(current)) {
                return false;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private static boolean isStreamIterationLambda(LambdaExpr lambda) {
        return lambda.getParentNode()
                .filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .filter(call -> STREAM_ITERATION_METHODS.contains(call.getNameAsString()))
                .isPresent();
    }

    private static boolean isScopeBoundary(Node n) {
        return n instanceof MethodDeclaration || n instanceof ConstructorDeclaration
                || n instanceof InitializerDeclaration || n instanceof TypeDeclaration;
    }

    // --- constant-ness (heuristic: literals and SCREAMING_SNAKE_CASE names) ---

    /**
     * True for a literal, or a bare name / field access in
     * {@code SCREAMING_SNAKE_CASE} (the conventional spelling of a
     * {@code static final} constant). Without symbol resolution this cannot
     * confirm the field is actually {@code static final}; that is an accepted,
     * documented false-negative (a badly-named mutable field looks constant),
     * never a false positive in the other direction.
     */
    static boolean looksConstant(Expression e) {
        if (e.isStringLiteralExpr() || e.isIntegerLiteralExpr() || e.isLongLiteralExpr()
                || e.isBooleanLiteralExpr() || e.isCharLiteralExpr() || e.isDoubleLiteralExpr()
                || e.isNullLiteralExpr()) {
            return true;
        }
        if (e.isNameExpr()) {
            return isConstantName(e.asNameExpr().getNameAsString());
        }
        if (e.isFieldAccessExpr()) {
            return isConstantName(e.asFieldAccessExpr().getNameAsString());
        }
        if (e.isEnclosedExpr()) {
            return looksConstant(e.asEnclosedExpr().getInner());
        }
        return false;
    }

    private static boolean isConstantName(String name) {
        boolean hasLetter = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLowerCase(c)) {
                return false;
            }
            hasLetter |= Character.isLetter(c);
        }
        return hasLetter;
    }

    /** {@link #looksConstant(Expression)}, extended through {@code +} concatenation of only such pieces. */
    static boolean isConstantExpression(Expression e) {
        if (looksConstant(e)) {
            return true;
        }
        if (e.isEnclosedExpr()) {
            return isConstantExpression(e.asEnclosedExpr().getInner());
        }
        if (e.isBinaryExpr()) {
            BinaryExpr b = e.asBinaryExpr();
            return b.getOperator() == BinaryExpr.Operator.PLUS
                    && isConstantExpression(b.getLeft()) && isConstantExpression(b.getRight());
        }
        return false;
    }

    // --- annotations -----------------------------------------------------

    static boolean hasAnnotation(NodeWithAnnotations<?> node, String... simpleNames) {
        Set<String> wanted = Set.of(simpleNames);
        for (AnnotationExpr a : node.getAnnotations()) {
            if (wanted.contains(simpleName(a.getNameAsString()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The simple name of a possibly-qualified, possibly-generic type spelling
     * (e.g. {@code "java.util.concurrent.ConcurrentHashMap<String, String>"}
     * or {@code "ConcurrentHashMap<String, String>"} both yield
     * {@code "ConcurrentHashMap"}): strips a trailing {@code <...>} type-argument
     * list first, then the package qualifier.
     */
    static String simpleName(String maybeQualified) {
        int lt = maybeQualified.indexOf('<');
        String erased = lt < 0 ? maybeQualified : maybeQualified.substring(0, lt);
        int dot = erased.lastIndexOf('.');
        return dot < 0 ? erased : erased.substring(dot + 1);
    }

    // --- enclosing scope ---------------------------------------------------

    static Optional<MethodDeclaration> enclosingMethod(Node n) {
        return n.findAncestor(MethodDeclaration.class);
    }

    static Optional<ClassOrInterfaceDeclaration> enclosingClass(Node n) {
        return n.findAncestor(ClassOrInterfaceDeclaration.class);
    }

    private static final Set<String> SINGLETON_STEREOTYPES = Set.of(
            "Service", "Component", "Repository", "Controller", "RestController", "Configuration",
            "Singleton", "ApplicationScoped");
    private static final Set<String> NON_SINGLETON_SCOPES = Set.of(
            "prototype", "request", "session", "websocket", "SCOPE_PROTOTYPE", "SCOPE_REQUEST", "SCOPE_SESSION");

    /**
     * A Spring/Jakarta stereotype-annotated class, shared by the container
     * (catalog &sect;1.5): mutable instance state there is shared across
     * every caller. A non-singleton {@code @Scope} on the class overrides this.
     */
    static boolean isSingletonBean(ClassOrInterfaceDeclaration c) {
        if (!hasAnnotation(c, SINGLETON_STEREOTYPES.toArray(String[]::new))) {
            return false;
        }
        return c.getAnnotationByName("Scope")
                .flatMap(AnnotationExpr::toSingleMemberAnnotationExpr)
                .map(a -> a.getMemberValue().toString())
                .stream()
                .noneMatch(value -> NON_SINGLETON_SCOPES.stream().anyMatch(value::contains));
    }

    // --- receiver / method-name matching --------------------------------

    /** {@code call.foo(...)}'s receiver expression, if the call has one. */
    static Optional<Expression> scopeOf(MethodCallExpr call) {
        return call.getScope();
    }

    /**
     * Best-effort static type name of a variable from its declaration,
     * without symbol resolution: a parameter or local variable of the nearest
     * enclosing method/constructor, else a field of the enclosing class.
     */
    static Optional<String> declaredTypeSimpleName(NameExpr nameExpr) {
        String name = nameExpr.getNameAsString();
        @SuppressWarnings("rawtypes")
        Optional<com.github.javaparser.ast.body.CallableDeclaration> callable =
                nameExpr.findAncestor(com.github.javaparser.ast.body.CallableDeclaration.class);
        if (callable.isPresent()) {
            for (Object rawParam : callable.get().getParameters()) {
                com.github.javaparser.ast.body.Parameter p = (com.github.javaparser.ast.body.Parameter) rawParam;
                if (p.getNameAsString().equals(name)) {
                    return Optional.of(simpleName(p.getTypeAsString()));
                }
            }
            Optional<String> local = callable.get().findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                    .filter(v -> v.getNameAsString().equals(name))
                    .findFirst()
                    .map(v -> simpleName(v.getTypeAsString()));
            if (local.isPresent()) {
                return local;
            }
        }
        return enclosingClass(nameExpr)
                .flatMap(c -> c.getFields().stream()
                        .flatMap(f -> f.getVariables().stream())
                        .filter(v -> v.getNameAsString().equals(name))
                        .findFirst())
                .map(v -> simpleName(v.getTypeAsString()));
    }

    // --- text extraction -----------------------------------------------------

    static int lineOf(Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }

    static int endLineOf(Node n) {
        return n.getEnd().map(p -> p.line).orElse(lineOf(n));
    }

    /** Up to 3 lines starting at {@code fromLine} (1-based, inclusive), trimmed. */
    static Optional<String> snippet(java.util.List<String> lines, int fromLine) {
        if (fromLine < 1 || fromLine > lines.size()) {
            return Optional.empty();
        }
        int to = Math.min(lines.size(), fromLine + 2);
        StringBuilder sb = new StringBuilder();
        for (int i = fromLine; i <= to; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i - 1).strip());
        }
        return Optional.of(sb.toString());
    }
}
