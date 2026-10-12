package com.devmanchego.javaguard.analysis;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What the resilience rules (JG-RES-*) share: which receivers are remote clients, which are blocking data access, whether
 * a node sits in a transactional method, and the root-of-chain call shape. Syntactic, like every JG rule: a type is
 * "remote" by its declared simple name, never by resolution.
 */
final class ResSupport {

    private static final Set<String> REMOTE_TYPES = Set.of("RestTemplate", "RestOperations", "RestClient", "WebClient", "HttpClient",
            "KafkaTemplate", "RabbitTemplate", "AmqpTemplate", "JmsTemplate", "JmsOperations", "SqsTemplate", "SnsTemplate");
    private static final List<String> DATA_ACCESS_SUFFIXES = List.of("Repository", "Dao", "Mapper");
    private static final Set<String> DATA_ACCESS_TYPES = Set.of("JdbcTemplate", "NamedParameterJdbcTemplate", "EntityManager", "Session");

    private ResSupport() {
    }

    /** A client of a remote system: the well-known templates, or any {@code *Client} type (Feign interfaces, SDK clients). */
    static boolean isRemoteType(String simpleName) {
        return REMOTE_TYPES.contains(simpleName) || simpleName.length() > "Client".length() && simpleName.endsWith("Client");
    }

    static boolean isDataAccessType(String simpleName) {
        return DATA_ACCESS_TYPES.contains(simpleName) || DATA_ACCESS_SUFFIXES.stream().anyMatch(s -> simpleName.endsWith(s)
                && simpleName.length() > s.length());
    }

    /** The declared type of {@code name} or {@code this.name}, when it is a variable the file declares. */
    static Optional<String> receiverType(Expression receiver) {
        return Spr003QueryInsideLoop.variableOf(receiver).flatMap(AstSupport::declaredTypeSimpleName);
    }

    /** The call is made directly on a variable of a remote client type (the first call of a chain), not on a chain result. */
    static boolean isRemoteCall(MethodCallExpr call) {
        return call.getScope().flatMap(ResSupport::receiverType).filter(ResSupport::isRemoteType).isPresent()
                && !call.getNameAsString().startsWith("set") && !call.getNameAsString().equals("mutate");
    }

    static boolean isBlockingCall(MethodCallExpr call) {
        if (call.getNameAsString().equals("sleep") && call.getScope().filter(s -> s.toString().equals("Thread")).isPresent()) {
            return true;
        }
        if (call.getScope().filter(s -> s.toString().equals("Files")).isPresent()) {
            return call.getNameAsString().startsWith("read") || call.getNameAsString().equals("copy");
        }
        return call.getScope().flatMap(ResSupport::receiverType).filter(t -> isRemoteType(t) || isDataAccessType(t)).isPresent();
    }

    /** Inside a {@code @Transactional} method or class, unless the method opts out ({@code NOT_SUPPORTED}/{@code NEVER}). */
    static boolean inTransaction(Node node) {
        Optional<MethodDeclaration> method = AstSupport.enclosingMethod(node);
        if (method.isEmpty()) {
            return false;
        }
        var own = method.get().getAnnotationByName("Transactional");
        if (own.isPresent()) {
            String text = own.get().toString();
            return !text.contains("NOT_SUPPORTED") && !text.contains("NEVER");
        }
        return AstSupport.enclosingClass(node).filter(c -> AstSupport.hasAnnotation(c, "Transactional")).isPresent();
    }

    static boolean insideMain(Node node) {
        return AstSupport.enclosingMethod(node).filter(m -> m.getNameAsString().equals("main") && m.isStatic()).isPresent();
    }

    /** The receiver variable's name, for messages. */
    static String describe(MethodCallExpr call) {
        return call.getScope().map(Expression::toString).orElse("?") + "." + call.getNameAsString() + "()";
    }

    static Optional<String> variableName(Expression receiver) {
        return Spr003QueryInsideLoop.variableOf(receiver).map(NameExpr::getNameAsString);
    }
}
