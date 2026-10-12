package com.devmanchego.javaguard.analysis.http;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The HTTP contract a source file declares, read from syntax: the Spring MVC endpoints of its controllers (combined class
 * and method mappings, HTTP methods, media types, parameters) and the JSON properties of its types (Jackson's default
 * visibility: records' components, public fields, getters, Lombok-generated getters; {@code @JsonProperty} renames,
 * {@code @JsonIgnore} and {@code @JsonIgnoreProperties} exclusions).
 */
public final class HttpContracts {

    /** One parameter clients must or may send. */
    public record Param(String kind, String name, boolean required) {
    }

    /**
     * One endpoint; {@code method} is an HTTP method or {@code ANY}; {@code path} is normalized ({@link #normalize});
     * {@code bodyType} and {@code returnTypes} are simple type names (generic containers unwrapped).
     */
    public record Endpoint(String method, String path, String rawPath, String handler, int line, Set<String> consumes,
                           Set<String> produces, List<Param> params, Optional<String> bodyType, boolean bodyRequired,
                           Set<String> returnTypes) {
        public String key() {
            return method + " " + path;
        }

        public String describe() {
            return (method.equals("ANY") ? "" : method + " ") + rawPath;
        }
    }

    /** The JSON properties of one type; {@code known} is false when a naming strategy or a custom serializer decides them. */
    public record JsonType(String name, Map<String, Integer> properties, boolean known) {
    }

    private static final Map<String, String> MAPPINGS = Map.of("GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "PatchMapping", "PATCH", "DeleteMapping", "DELETE", "RequestMapping", "ANY");
    private static final Set<String> CONTAINERS = Set.of("ResponseEntity", "HttpEntity", "List", "Set", "Collection", "Iterable",
            "Optional", "Page", "Slice", "Mono", "Flux", "CompletableFuture", "Callable", "DeferredResult", "Stream");
    private static final Set<String> LOMBOK_GETTERS = Set.of("Data", "Getter", "Value");

    private HttpContracts() {
    }

    public static Optional<CompilationUnit> parse(String source) {
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        return parser.parse(source).getResult();
    }

    // --- endpoints -----------------------------------------------------------------------------------

    public static List<Endpoint> endpoints(CompilationUnit unit) {
        List<Endpoint> out = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            boolean controller = has(type, "RestController") || has(type, "Controller");
            if (!controller) {
                continue;
            }
            Optional<AnnotationExpr> classMapping = type.getAnnotationByName("RequestMapping");
            List<String> prefixes = classMapping.map(a -> strings(member(a, "value", "path"))).filter(l -> !l.isEmpty())
                    .orElse(List.of(""));
            Set<String> classConsumes = classMapping.map(a -> media(member(a, "consumes"))).orElse(Set.of());
            Set<String> classProduces = classMapping.map(a -> media(member(a, "produces"))).orElse(Set.of());
            for (MethodDeclaration method : type.getMethods()) {
                for (AnnotationExpr mapping : method.getAnnotations()) {
                    String kind = MAPPINGS.get(simple(mapping.getNameAsString()));
                    if (kind == null) {
                        continue;
                    }
                    List<String> methods = kind.equals("ANY") ? httpMethods(member(mapping, "method")) : List.of(kind);
                    List<String> paths = strings(mapping.isSingleMemberAnnotationExpr() ? Optional.of(
                            mapping.asSingleMemberAnnotationExpr().getMemberValue()) : member(mapping, "value", "path"));
                    if (paths.isEmpty()) {
                        paths = List.of("");
                    }
                    Set<String> consumes = union(classConsumes, media(member(mapping, "consumes")));
                    Set<String> produces = union(classProduces, media(member(mapping, "produces")));
                    List<Param> params = new ArrayList<>();
                    Optional<String> body = Optional.empty();
                    boolean bodyRequired = false;
                    for (Parameter p : method.getParameters()) {
                        Optional<AnnotationExpr> requestBody = p.getAnnotationByName("RequestBody");
                        if (requestBody.isPresent()) {
                            body = innerTypes(p.getType()).stream().findFirst();
                            bodyRequired = !isOptional(p.getType()) && !member(requestBody.get(), "required")
                                    .map(v -> v.toString().equals("false")).orElse(false);
                            continue;
                        }
                        param(p, "RequestParam", "query parameter").ifPresent(params::add);
                        param(p, "RequestHeader", "header").ifPresent(params::add);
                    }
                    Set<String> returns = new LinkedHashSet<>(innerTypes(method.getType()));
                    int line = method.getBegin().map(b -> b.line).orElse(0);
                    for (String prefix : prefixes) {
                        for (String path : paths) {
                            String raw = combine(prefix, path);
                            for (String m : methods) {
                                out.add(new Endpoint(m, normalize(raw), raw, type.getNameAsString() + "." + method.getNameAsString(),
                                        line, consumes, produces, List.copyOf(params), body, bodyRequired, returns));
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private static Optional<Param> param(Parameter p, String annotation, String kind) {
        Optional<AnnotationExpr> a = p.getAnnotationByName(annotation);
        if (a.isEmpty()) {
            return Optional.empty();
        }
        String name = strings(a.get().isSingleMemberAnnotationExpr() ? Optional.of(a.get().asSingleMemberAnnotationExpr().getMemberValue())
                : member(a.get(), "value", "name")).stream().findFirst().orElse(p.getNameAsString());
        boolean required = !isOptional(p.getType()) && member(a.get(), "defaultValue").isEmpty()
                && !member(a.get(), "required").map(v -> v.toString().equals("false")).orElse(false);
        String type = p.getType().asString();
        if (type.startsWith("Map") || type.startsWith("MultiValueMap") || type.equals("HttpHeaders")) {
            return Optional.empty(); // All parameters or headers: nothing in particular is required.
        }
        return Optional.of(new Param(kind, name, required));
    }

    /** Spring's combination of class and method paths: one slash between them, a leading slash. */
    static String combine(String prefix, String path) {
        String p = prefix.isEmpty() ? "" : (prefix.startsWith("/") ? prefix : "/" + prefix);
        String m = path.isEmpty() ? "" : (path.startsWith("/") ? path : "/" + path);
        if (p.endsWith("/") && m.startsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        String combined = p + m;
        return combined.isEmpty() ? "/" : combined;
    }

    /**
     * Path variables lose their names and patterns ({@code {id}}, {@code {orderId:\d+}} are the same to a client), a
     * trailing slash is dropped (Spring 5 matched it by default, and dropping it here never reports a change that only
     * moved a slash), duplicate slashes collapse.
     */
    public static String normalize(String path) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (char c : path.toCharArray()) {
            if (c == '{') {
                if (depth++ == 0) {
                    out.append("{}");
                }
                continue;
            }
            if (c == '}') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0) {
                out.append(c);
            }
        }
        String n = out.toString().replaceAll("/+", "/");
        if (n.length() > 1 && n.endsWith("/")) {
            n = n.substring(0, n.length() - 1);
        }
        return n.startsWith("/") ? n : "/" + n;
    }

    private static List<String> httpMethods(Optional<Expression> value) {
        if (value.isEmpty()) {
            return List.of("ANY");
        }
        List<String> out = new ArrayList<>();
        for (Expression e : elements(value.get())) {
            String text = e.toString();
            out.add(text.substring(text.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT));
        }
        return out.isEmpty() ? List.of("ANY") : out;
    }

    private static Set<String> media(Optional<Expression> value) {
        Set<String> out = new TreeSet<>();
        value.ifPresent(v -> elements(v).forEach(e -> {
            if (e.isStringLiteralExpr()) {
                out.add(e.asStringLiteralExpr().asString().toLowerCase(Locale.ROOT));
            } else {
                String name = e.toString();
                name = name.substring(name.lastIndexOf('.') + 1).replaceAll("_VALUE$", "");
                out.add(name.toLowerCase(Locale.ROOT).replaceFirst("_", "/").replace('_', '-'));
            }
        }));
        return out;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.addAll(b);
        return out;
    }

    private static List<String> strings(Optional<Expression> value) {
        List<String> out = new ArrayList<>();
        value.ifPresent(v -> elements(v).forEach(e -> out.add(e.isStringLiteralExpr() ? e.asStringLiteralExpr().asString()
                : "${" + e + "}")));
        return out;
    }

    private static List<Expression> elements(Expression e) {
        return e.isArrayInitializerExpr() ? new ArrayList<>(e.asArrayInitializerExpr().getValues()) : List.of(e);
    }

    static Optional<Expression> member(AnnotationExpr annotation, String... names) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            return List.of(names).contains("value") ? Optional.of(annotation.asSingleMemberAnnotationExpr().getMemberValue())
                    : Optional.empty();
        }
        if (annotation.isNormalAnnotationExpr()) {
            for (String name : names) {
                Optional<Expression> v = annotation.asNormalAnnotationExpr().getPairs().stream()
                        .filter(p -> p.getNameAsString().equals(name)).map(MemberValuePair::getValue).findFirst();
                if (v.isPresent()) {
                    return v;
                }
            }
        }
        return Optional.empty();
    }

    /** {@code ResponseEntity<List<OrderDto>>} gives {@code OrderDto}; a plain type gives itself. */
    static List<String> innerTypes(Type type) {
        List<String> out = new ArrayList<>();
        if (type.isArrayType()) {
            return innerTypes(type.asArrayType().getComponentType());
        }
        if (!type.isClassOrInterfaceType()) {
            return out;
        }
        ClassOrInterfaceType c = type.asClassOrInterfaceType();
        if (CONTAINERS.contains(c.getNameAsString())) {
            c.getTypeArguments().ifPresent(args -> args.forEach(a -> out.addAll(innerTypes(a))));
        } else {
            out.add(c.getNameAsString());
        }
        return out;
    }

    private static boolean isOptional(Type type) {
        return type.isClassOrInterfaceType() && type.asClassOrInterfaceType().getNameAsString().equals("Optional");
    }

    // --- JSON properties -----------------------------------------------------------------------------

    public static List<JsonType> jsonTypes(CompilationUnit unit) {
        List<JsonType> out = new ArrayList<>();
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            boolean known = !has(type, "JsonNaming") && !has(type, "JsonSerialize") && !has(type, "JsonDeserialize")
                    && !has(type, "JsonAnyGetter");
            Set<String> ignored = new LinkedHashSet<>();
            type.getAnnotationByName("JsonIgnoreProperties").flatMap(a -> member(a, "value")).ifPresent(v -> ignored.addAll(strings(Optional.of(v))));
            Map<String, Integer> properties = new LinkedHashMap<>();
            if (type instanceof RecordDeclaration record) {
                for (Parameter p : record.getParameters()) {
                    add(properties, ignored, p, p.getNameAsString(), line(p));
                }
            } else if (type instanceof ClassOrInterfaceDeclaration c && !c.isInterface()) {
                boolean lombok = LOMBOK_GETTERS.stream().anyMatch(n -> has(c, n));
                for (FieldDeclaration field : c.getFields()) {
                    if (field.isStatic() || field.hasModifier(Modifier.Keyword.TRANSIENT)) {
                        continue;
                    }
                    boolean visible = field.isPublic() || lombok || has(field, "Getter") || has(field, "JsonProperty");
                    for (VariableDeclarator v : field.getVariables()) {
                        if (visible) {
                            add(properties, ignored, field, v.getNameAsString(), line(field));
                        }
                    }
                }
                for (MethodDeclaration m : c.getMethods()) {
                    Optional<String> property = getterProperty(m);
                    if (property.isPresent() && m.isPublic() && !m.isStatic()) {
                        add(properties, ignored, m, property.get(), line(m));
                    }
                }
            } else {
                continue;
            }
            out.add(new JsonType(type.getNameAsString(), properties, known));
        }
        return out;
    }

    private static Optional<String> getterProperty(MethodDeclaration m) {
        if (!m.getParameters().isEmpty() || m.getType().isVoidType()) {
            return Optional.empty();
        }
        String name = m.getNameAsString();
        if (name.startsWith("get") && name.length() > 3 && Character.isUpperCase(name.charAt(3)) && !name.equals("getClass")) {
            return Optional.of(decapitalize(name.substring(3)));
        }
        if (name.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2)) && m.getType().asString().equals("boolean")) {
            return Optional.of(decapitalize(name.substring(2)));
        }
        return has(m, "JsonProperty") ? Optional.of(name) : Optional.empty();
    }

    /** Jackson's default getter naming: the leading run of upper-case letters is lower-cased ({@code getURL} is {@code url}). */
    private static String decapitalize(String s) {
        int upper = 0;
        while (upper < s.length() && Character.isUpperCase(s.charAt(upper))) {
            upper++;
        }
        return s.substring(0, upper).toLowerCase(Locale.ROOT) + s.substring(upper);
    }

    private static void add(Map<String, Integer> properties, Set<String> ignored, NodeWithAnnotations<?> node, String name, int line) {
        if (has(node, "JsonIgnore")) {
            properties.remove(name);
            ignored.add(name);
            return;
        }
        String json = node.getAnnotationByName("JsonProperty").flatMap(a -> strings(member(a, "value")).stream().findFirst())
                .filter(n -> !n.isEmpty()).orElse(name);
        if (!ignored.contains(name) && !ignored.contains(json)) {
            properties.putIfAbsent(json, line);
        }
    }

    private static int line(com.github.javaparser.ast.Node node) {
        return node.getBegin().map(b -> b.line).orElse(0);
    }

    private static boolean has(NodeWithAnnotations<?> node, String name) {
        return node.getAnnotations().stream().anyMatch(a -> simple(a.getNameAsString()).equals(name));
    }

    private static String simple(String name) {
        return name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
    }
}
