package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JG-SEC-009 (catalog &sect;1.2): request input used directly to build a file
 * path. "Directly" is read literally: the sink's argument must be the tainted
 * name itself, not something derived from it (that would need data-flow
 * tracking this rule deliberately does not attempt).
 */
final class Sec009PathTraversal extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SEC-009", "Path from request input", Category.SECURITY, Severity.MAJOR);

    private static final Set<String> TAINT_ANNOTATIONS = Set.of(
            "RequestParam", "PathVariable", "RequestHeader", "QueryParam", "PathParam");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodDeclaration method : ctx.unit().findAll(MethodDeclaration.class)) {
            Set<String> tainted = taintedNames(method);
            if (tainted.isEmpty()) {
                continue;
            }
            for (ObjectCreationExpr creation : method.findAll(ObjectCreationExpr.class)) {
                if ((creation.getType().getNameAsString().equals("File")
                        || creation.getType().getNameAsString().equals("FileInputStream")
                        || creation.getType().getNameAsString().equals("FileOutputStream"))
                        && anyArgTainted(creation.getArguments(), tainted)) {
                    out.add(sinkFinding(ctx, creation));
                }
            }
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                String name = call.getNameAsString();
                boolean sink = (name.equals("get") && isNamedScope(call, "Paths"))
                        || (name.equals("of") && isNamedScope(call, "Path"))
                        || name.equals("resolve")
                        || call.getScope().filter(s -> s.isNameExpr() && s.asNameExpr().getNameAsString().equals("Files")).isPresent();
                if (sink && anyArgTainted(call.getArguments(), tainted)) {
                    out.add(sinkFinding(ctx, call));
                }
            }
        }
        return out;
    }

    private Finding sinkFinding(AstCheckContext ctx, com.github.javaparser.ast.Node at) {
        return finding(ctx, at, "A request-controlled value is used directly to build a file path.",
                "Resolve it against a fixed base directory and check the result before use: Path p = "
                        + "base.resolve(fileName(input)).normalize(); if (!p.startsWith(base)) reject it, where fileName(...) "
                        + "rejects '..', separators and absolute paths. Or map the input through an allow-list of known names.");
    }

    private Set<String> taintedNames(MethodDeclaration method) {
        Set<String> names = new HashSet<>();
        for (Parameter p : method.getParameters()) {
            if (AstSupport.hasAnnotation(p, TAINT_ANNOTATIONS.toArray(String[]::new))) {
                names.add(p.getNameAsString());
            }
        }
        for (VariableDeclarator v : method.findAll(VariableDeclarator.class)) {
            if (v.getInitializer().filter(i -> i.isMethodCallExpr()
                    && i.asMethodCallExpr().getNameAsString().equals("getOriginalFilename")).isPresent()) {
                names.add(v.getNameAsString());
            }
        }
        return names;
    }

    private boolean anyArgTainted(Iterable<Expression> args, Set<String> tainted) {
        for (Expression a : args) {
            if (a.isNameExpr() && tainted.contains(a.asNameExpr().getNameAsString())) {
                return true;
            }
        }
        return false;
    }

    private boolean isNamedScope(MethodCallExpr call, String name) {
        return call.getScope().filter(s -> s.isNameExpr() && s.asNameExpr().getNameAsString().equals(name)).isPresent();
    }
}
