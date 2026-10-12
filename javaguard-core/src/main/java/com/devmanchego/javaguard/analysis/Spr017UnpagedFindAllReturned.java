package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JG-SPR-017 (catalog &sect;1.6): a REST handler that returns the result of a repository's {@code findAll()} without a
 * {@code Pageable}, {@code Sort} or {@code Specification}, directly or through a method of the same class. The response
 * grows with the table: fine with test data, a memory and latency problem in production. A result reduced before it is
 * returned ({@code count()}, {@code size()}, {@code findFirst()}...) is not reported, nor are repositories of small reference
 * tables ({@code AuthorityRepository}, {@code CountryRepository}, {@code *TypeRepository}...).
 */
final class Spr017UnpagedFindAllReturned extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SPR-017", "Unpaged findAll() returned to the client", Category.SPRING_JPA, Severity.MAJOR);

    private static final Set<String> MAPPINGS = Set.of("GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping",
            "RequestMapping");
    /** Repositories of small reference tables, which handlers return whole by design. */
    private static final java.util.regex.Pattern REFERENCE_DATA = java.util.regex.Pattern.compile(
            "(Authority|Authorities|Role|Permission|Privilege|Country|Countries|Currency|Currencies|Language|Locale|Status|Type|Category"
                    + "|Categories|Setting|Config|Configuration|Parameter|Lookup|Code|Unit)Repository");
    private static final Set<String> REDUCERS = Set.of("size", "count", "isEmpty", "anyMatch", "allMatch", "noneMatch", "findFirst",
            "findAny", "max", "min", "sum", "limit", "get", "getFirst", "iterator", "contains");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public Set<Framework> requiresAnyOf() {
        return Frameworks.SPRING;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : ctx.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            boolean rest = AstSupport.hasAnnotation(type, "RestController");
            boolean controller = rest || AstSupport.hasAnnotation(type, "Controller");
            if (!controller) {
                continue;
            }
            Map<String, MethodCallExpr> returnsAll = new HashMap<>();
            for (MethodDeclaration method : type.getMethods()) {
                returnedFindAll(method).ifPresent(call -> returnsAll.put(method.getNameAsString(), call));
            }
            for (MethodDeclaration method : type.getMethods()) {
                boolean handler = AstSupport.hasAnnotation(method, MAPPINGS.toArray(String[]::new))
                        && (rest || AstSupport.hasAnnotation(type, "ResponseBody") || AstSupport.hasAnnotation(method, "ResponseBody"));
                if (!handler) {
                    continue;
                }
                Optional<MethodCallExpr> call = Optional.ofNullable(returnsAll.get(method.getNameAsString()))
                        .filter(c -> AstSupport.enclosingMethod(c).filter(m -> m == method).isPresent())
                        .or(() -> helperCall(method, returnsAll));
                call.ifPresent(c -> out.add(finding(ctx, c, method.getNameAsString() + "() returns every row of "
                                + source(c, returnsAll) + " to the client: the response grows with the table.",
                        "Take a Pageable parameter and return findAll(pageable) (a Page or Slice), or bound the query.")));
            }
        }
        return out;
    }

    /** The unpaged {@code findAll()} whose result this method returns, if any. */
    private static Optional<MethodCallExpr> returnedFindAll(MethodDeclaration method) {
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals("findAll") || !call.getArguments().isEmpty()
                    || call.getScope().flatMap(ResSupport::receiverType).filter(ResSupport::isDataAccessType).isEmpty()
                    || reduced(call) || AstSupport.enclosingMethod(call).filter(m -> m == method).isEmpty()
                    || call.getScope().flatMap(ResSupport::receiverType).filter(t -> REFERENCE_DATA.matcher(t).matches()).isPresent()) {
                continue;
            }
            if (call.findAncestor(ReturnStmt.class).isPresent()) {
                return Optional.of(call);
            }
            Optional<String> variable = assignedTo(call);
            if (variable.isPresent() && method.findAll(ReturnStmt.class).stream().anyMatch(r -> r.getExpression()
                    .filter(e -> e.findAll(NameExpr.class).stream().anyMatch(n -> n.getNameAsString().equals(variable.get()))
                            || e.isNameExpr() && e.asNameExpr().getNameAsString().equals(variable.get())).isPresent())) {
                return Optional.of(call);
            }
        }
        return Optional.empty();
    }

    /** The handler returns a call to a method of the same class that returns an unpaged {@code findAll()}. */
    private static Optional<MethodCallExpr> helperCall(MethodDeclaration handler, Map<String, MethodCallExpr> returnsAll) {
        for (ReturnStmt ret : handler.findAll(ReturnStmt.class)) {
            for (MethodCallExpr call : ret.findAll(MethodCallExpr.class)) {
                boolean own = call.getScope().isEmpty() || call.getScope().get().isThisExpr();
                if (own && returnsAll.containsKey(call.getNameAsString()) && !reduced(call)) {
                    return Optional.of(call);
                }
            }
        }
        return Optional.empty();
    }

    private static String source(MethodCallExpr call, Map<String, MethodCallExpr> returnsAll) {
        if (call.getNameAsString().equals("findAll")) {
            return ResSupport.describe(call);
        }
        return ResSupport.describe(returnsAll.get(call.getNameAsString())) + " (through " + call.getNameAsString() + "())";
    }

    private static boolean reduced(MethodCallExpr call) {
        Node current = call;
        while (current.getParentNode().isPresent() && current.getParentNode().get() instanceof MethodCallExpr parent
                && parent.getScope().isPresent() && parent.getScope().get() == current) {
            if (REDUCERS.contains(parent.getNameAsString())) {
                return true;
            }
            current = parent;
        }
        return false;
    }

    private static Optional<String> assignedTo(MethodCallExpr call) {
        Node top = call;
        while (top.getParentNode().isPresent() && top.getParentNode().get() instanceof MethodCallExpr parent
                && parent.getScope().isPresent() && parent.getScope().get() == top) {
            top = parent;
        }
        Optional<Node> parent = top.getParentNode();
        if (parent.isPresent() && parent.get() instanceof VariableDeclarator declarator) {
            return Optional.of(declarator.getNameAsString());
        }
        if (parent.isPresent() && parent.get() instanceof AssignExpr assign && assign.getTarget().isNameExpr()) {
            return Optional.of(assign.getTarget().asNameExpr().getNameAsString());
        }
        return Optional.empty();
    }
}
