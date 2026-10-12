package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * JG-RES-001 (catalog &sect;1.11): an HTTP client built so that a slow or dead server hangs the caller for ever - the
 * root cause of most "the service stopped answering" incidents. Flagged: {@code new RestTemplate()} with no request factory
 * configured anywhere in the class; {@code HttpClient.newHttpClient()} and an {@code HttpClient.newBuilder()} chain without
 * {@code connectTimeout}; an {@code HttpRequest.newBuilder()} chain without {@code timeout}; {@code WebClient.create(...)}
 * and a {@code WebClient.builder()} chain without a connector, in a class that sets no timeout at all; a
 * {@code URLConnection} without both {@code setConnectTimeout} and {@code setReadTimeout} in the method; Apache HttpClient
 * built by {@code createDefault()} or by a builder without a request config or connection manager. OkHttp is not flagged: it
 * defaults to ten seconds.
 */
final class Res001HttpClientWithoutTimeouts extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-RES-001", "HTTP client without timeouts", Category.RELIABILITY, Severity.CRITICAL);

    private static final Set<String> REST_TEMPLATE_CONFIGURATION = Set.of("setRequestFactory", "setConnectTimeout", "setReadTimeout",
            "setConnectionRequestTimeout", "connectTimeout", "readTimeout", "requestFactory");
    private static final Set<String> WEBCLIENT_CONFIGURATION = Set.of("timeout", "responseTimeout", "clientConnector", "doOnConnected",
            "connectTimeout");
    private static final Set<String> APACHE_CONFIGURATION = Set.of("setDefaultRequestConfig", "setConnectionManager",
            "setDefaultSocketConfig", "setConnectTimeout", "setResponseTimeout");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (ObjectCreationExpr creation : ctx.unit().findAll(ObjectCreationExpr.class)) {
            if (creation.getType().getNameAsString().equals("RestTemplate") && creation.getArguments().isEmpty()
                    && !classCalls(creation, REST_TEMPLATE_CONFIGURATION)) {
                out.add(finding(ctx, creation, "new RestTemplate() has no connect or read timeout: a server that never answers blocks the "
                        + "calling thread for ever.", "Build it with a request factory that sets both timeouts "
                        + "(RestTemplateBuilder.setConnectTimeout/setReadTimeout, or setRequestFactory)."));
            }
        }
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            checkCall(ctx, call).ifPresent(out::add);
        }
        return out;
    }

    private Optional<Finding> checkCall(AstCheckContext ctx, MethodCallExpr call) {
        String name = call.getNameAsString();
        if (name.equals("newHttpClient") && isScope(call, "HttpClient")) {
            return Optional.of(finding(ctx, call, "HttpClient.newHttpClient() has no connect timeout: connecting to a dead host never gives up.",
                    "Use HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(...)).build(), and set a timeout on every request."));
        }
        if (name.equals("build") && chainRootIs(call, "HttpClient", "newBuilder") && !chainCalls(call, "connectTimeout")) {
            return Optional.of(finding(ctx, call, "This HttpClient is built without connectTimeout(...): connecting to a dead host never gives up.",
                    "Add .connectTimeout(Duration.ofSeconds(...)) to the builder, and set a timeout on every request."));
        }
        if (name.equals("build") && chainRootIs(call, "HttpRequest", "newBuilder") && !chainCalls(call, "timeout")) {
            return Optional.of(finding(ctx, call, "This HttpRequest has no timeout(...): a server that accepts the connection and never answers "
                    + "blocks the caller for ever.", "Add .timeout(Duration.ofSeconds(...)) to the request builder."));
        }
        if (name.equals("create") && isScope(call, "WebClient") && !classCalls(call, WEBCLIENT_CONFIGURATION)) {
            return Optional.of(finding(ctx, call, "WebClient.create(...) has no response timeout and nothing in this class sets one.",
                    "Build it with WebClient.builder().clientConnector(...) using a reactor-netty HttpClient with responseTimeout, "
                            + "or apply .timeout(Duration) on every call."));
        }
        if (name.equals("build") && chainRootIs(call, "WebClient", "builder") && !chainCallsAny(call, WEBCLIENT_CONFIGURATION)
                && !classCalls(call, WEBCLIENT_CONFIGURATION)) {
            return Optional.of(finding(ctx, call, "This WebClient is built without a connector or timeout, and nothing in this class sets one.",
                    "Add .clientConnector(new ReactorClientHttpConnector(httpClient.responseTimeout(...))) or apply .timeout(Duration) per call."));
        }
        if (name.equals("openConnection") && call.getArguments().isEmpty()) {
            return urlConnection(ctx, call);
        }
        if ((name.equals("createDefault") || name.equals("createSystem")) && isScope(call, "HttpClients")) {
            return Optional.of(finding(ctx, call, "HttpClients." + name + "() uses the default request config: no response timeout.",
                    "Build it with HttpClients.custom().setDefaultRequestConfig(...) or a PoolingHttpClientConnectionManager with timeouts."));
        }
        if (name.equals("build") && (chainRootIs(call, "HttpClients", "custom") || chainRootIs(call, "HttpClientBuilder", "create"))
                && !chainCallsAny(call, APACHE_CONFIGURATION)) {
            return Optional.of(finding(ctx, call, "This Apache HttpClient is built without a request config or connection manager: no response timeout.",
                    "Add .setDefaultRequestConfig(RequestConfig.custom()...) or a connection manager with socket and connect timeouts."));
        }
        return Optional.empty();
    }

    /**
     * {@code url.openConnection()}: assigned to a variable, both timeouts must be set on it in the same method; used directly
     * as the receiver of a call (a chain) it cannot have any. Anything else (returned, passed on) is somebody else's to configure.
     */
    private Optional<Finding> urlConnection(AstCheckContext ctx, MethodCallExpr call) {
        if (call.getScope().isEmpty()) {
            return Optional.empty(); // A method of the class's own, not URL.openConnection.
        }
        Optional<com.github.javaparser.ast.body.VariableDeclarator> declarator = call.findAncestor(com.github.javaparser.ast.body.VariableDeclarator.class)
                .filter(v -> v.getInitializer().filter(i -> i == call || i.findAll(MethodCallExpr.class).contains(call)
                        && i instanceof com.github.javaparser.ast.expr.CastExpr).isPresent());
        Optional<MethodDeclaration> method = AstSupport.enclosingMethod(call);
        boolean configured;
        if (declarator.isPresent() && method.isPresent()) {
            String variable = declarator.get().getNameAsString();
            configured = hasCallOn(method.get(), variable, "setConnectTimeout") && hasCallOn(method.get(), variable, "setReadTimeout");
        } else if (call.getParentNode().filter(p -> p instanceof MethodCallExpr parent && parent.getScope().filter(s -> s == call).isPresent()).isPresent()) {
            configured = false;
        } else {
            return Optional.empty();
        }
        return configured ? Optional.empty() : Optional.of(finding(ctx, call,
                "This URL connection does not set both setConnectTimeout and setReadTimeout in the same method.",
                "Call setConnectTimeout(...) and setReadTimeout(...) on the connection before using it."));
    }

    private static boolean hasCallOn(MethodDeclaration method, String variable, String name) {
        return method.findAll(MethodCallExpr.class).stream().anyMatch(c -> c.getNameAsString().equals(name)
                && c.getScope().filter(s -> s.toString().equals(variable)).isPresent());
    }

    // --- helpers -------------------------------------------------------------------------------

    private static boolean isScope(MethodCallExpr call, String type) {
        return call.getScope().filter(s -> s.isNameExpr() && s.asNameExpr().getNameAsString().equals(type)).isPresent();
    }

    /** The call chain {@code Type.method()....call}: its innermost call is {@code Type.method(...)}. */
    private static boolean chainRootIs(MethodCallExpr call, String type, String method) {
        Expression current = call;
        while (current instanceof MethodCallExpr c) {
            if (c.getNameAsString().equals(method) && isScope(c, type)) {
                return true;
            }
            current = c.getScope().orElse(null);
        }
        return false;
    }

    private static boolean chainCalls(MethodCallExpr call, String name) {
        return chainCallsAny(call, Set.of(name));
    }

    private static boolean chainCallsAny(MethodCallExpr call, Set<String> names) {
        Expression current = call;
        while (current instanceof MethodCallExpr c) {
            if (names.contains(c.getNameAsString())) {
                return true;
            }
            current = c.getScope().orElse(null);
        }
        return false;
    }

    /** Anything in the enclosing class calls one of {@code names}: the timeouts are configured somewhere in this bean. */
    private static boolean classCalls(Node node, Set<String> names) {
        return AstSupport.enclosingClass(node).map(c -> c.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> names.contains(call.getNameAsString()))).orElse(false);
    }
}
