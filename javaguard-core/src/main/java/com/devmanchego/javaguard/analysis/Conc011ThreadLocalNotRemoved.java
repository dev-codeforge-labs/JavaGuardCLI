package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JG-CONC-011 (catalog &sect;1.5): a {@code private ThreadLocal} that the class sets and never removes. Server threads are
 * pooled and live as long as the process, so a value left in a thread's map outlives the request that set it: it leaks
 * memory (and class loaders on redeploy) and, worse, the next request on that thread can read the previous user's data. A
 * ThreadLocal that is not private may be removed by another class, so it is not judged here; the catalog's "in a
 * {@code finally} of the same method" is relaxed to "anywhere in the class" for the same reason (a holder's {@code clear()}
 * called from a filter is the usual design).
 */
final class Conc011ThreadLocalNotRemoved extends AbstractAstCheck {

    static final RuleInfo INFO = new RuleInfo("JG-CONC-011", "ThreadLocal not removed", Category.CONCURRENCY, Severity.MAJOR, "ast", "F");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : ctx.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            for (FieldDeclaration field : type.getFields()) {
                if (!field.hasModifier(Modifier.Keyword.PRIVATE) || !AstSupport.simpleName(field.getElementType().asString()).endsWith("ThreadLocal")) {
                    continue;
                }
                for (VariableDeclarator variable : field.getVariables()) {
                    String name = variable.getNameAsString();
                    List<MethodCallExpr> calls = type.findAll(MethodCallExpr.class);
                    Optional<MethodCallExpr> set = calls.stream().filter(c -> c.getNameAsString().equals("set") && onField(c, name)).findFirst();
                    boolean removed = calls.stream().anyMatch(c -> onField(c, name) && (c.getNameAsString().equals("remove")
                            || c.getNameAsString().equals("set") && c.getArguments().size() == 1
                            && c.getArgument(0).toString().replace(" ", "").matches("null|Optional\\.empty\\(\\)|java\\.util\\.Optional\\.empty\\(\\)")));
                    if (set.isPresent() && !removed) {
                        out.add(finding(ctx, set.get(), name + ".set(...) is never matched by " + name + ".remove() in " + type.getNameAsString()
                                + ": pooled threads keep the value after the request ends.",
                                "Call " + name + ".remove() in a finally block (or a filter's finally) when the work that needs the value is done."));
                    }
                }
            }
        }
        return out;
    }

    private static boolean onField(MethodCallExpr call, String field) {
        return call.getScope().filter(s -> s.toString().equals(field) || s.toString().equals("this." + field)).isPresent();
    }
}
