package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.TryStmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-REL-003 (catalog &sect;1.4): an {@code AutoCloseable} resource that is
 * created but not managed. "Managed" is read broadly, favoring precision:
 * declared in try-with-resources, closed anywhere in the same method, returned
 * to the caller, assigned to a field, or handed to another constructor (taken
 * as evidence a wrapper now owns it, e.g. {@code new BufferedReader(reader)}).
 */
final class Rel003ResourceNotClosed extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-REL-003", "Resource not closed", Category.RELIABILITY, Severity.CRITICAL);

    private static final Set<String> CLOSEABLE_CONSTRUCTED_TYPES = Set.of(
            "FileInputStream", "FileOutputStream", "FileReader", "FileWriter", "BufferedReader", "BufferedWriter",
            "Scanner", "ZipFile", "Socket", "ServerSocket", "RandomAccessFile", "ObjectInputStream", "ObjectOutputStream");
    private static final Set<String> FILES_FACTORY_METHODS = Set.of(
            "newInputStream", "newOutputStream", "newBufferedReader", "newBufferedWriter", "lines", "list", "walk", "find");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public boolean mainOnly() {
        return false; // Catalog scope: all.
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (VariableDeclarator v : ctx.unit().findAll(VariableDeclarator.class)) {
            if (v.getInitializer().filter(this::createsResource).isEmpty() || isTryWithResourcesVariable(v)) {
                continue;
            }
            MethodDeclaration method = AstSupport.enclosingMethod(v).orElse(null);
            if (method != null && !isManaged(method, v.getNameAsString())) {
                out.add(finding(ctx, v, "'" + v.getNameAsString() + "' is an AutoCloseable resource that is never closed.",
                        "Declare it in a try-with-resources statement, or close it in a finally block."));
            }
        }
        return out;
    }

    private boolean createsResource(Expression init) {
        if (init.isObjectCreationExpr()) {
            return CLOSEABLE_CONSTRUCTED_TYPES.contains(init.asObjectCreationExpr().getType().getNameAsString());
        }
        if (init.isMethodCallExpr()) {
            MethodCallExpr m = init.asMethodCallExpr();
            String name = m.getNameAsString();
            String type = DynamicStringBuilds.receiverTypeOf(m).orElse("");
            if ((name.equals("prepareStatement") || name.equals("prepareCall")) && type.equals("Connection")) {
                return true;
            }
            if (name.equals("executeQuery") && (type.equals("Statement") || type.equals("PreparedStatement"))) {
                return true;
            }
            if (name.equals("getConnection") && (type.equals("DataSource") || isNamedScope(m, "DriverManager"))) {
                return true;
            }
            return FILES_FACTORY_METHODS.contains(name) && isNamedScope(m, "Files");
        }
        return false;
    }

    /** A {@code VariableDeclarationExpr}'s only possible parent besides a normal statement is a try-resources list. */
    private boolean isTryWithResourcesVariable(VariableDeclarator v) {
        return v.getParentNode()
                .filter(p -> p instanceof VariableDeclarationExpr)
                .flatMap(com.github.javaparser.ast.Node::getParentNode)
                .filter(p -> p instanceof TryStmt)
                .isPresent();
    }

    private boolean isManaged(MethodDeclaration method, String name) {
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (call.getNameAsString().equals("close") && isNamedScope(call, name)) {
                return true;
            }
        }
        for (ReturnStmt r : method.findAll(ReturnStmt.class)) {
            if (r.getExpression().filter(e -> e.isNameExpr() && e.asNameExpr().getNameAsString().equals(name)).isPresent()) {
                return true;
            }
        }
        for (AssignExpr a : method.findAll(AssignExpr.class)) {
            if (a.getTarget().isFieldAccessExpr() && a.getValue().isNameExpr()
                    && a.getValue().asNameExpr().getNameAsString().equals(name)) {
                return true;
            }
        }
        for (ObjectCreationExpr creation : method.findAll(ObjectCreationExpr.class)) {
            if (creation.getArguments().stream().anyMatch(a -> a.isNameExpr() && a.asNameExpr().getNameAsString().equals(name))) {
                return true;
            }
        }
        return false;
    }

    private boolean isNamedScope(MethodCallExpr call, String name) {
        return call.getScope().filter(s -> s.isNameExpr() && s.asNameExpr().getNameAsString().equals(name)).isPresent();
    }
}
