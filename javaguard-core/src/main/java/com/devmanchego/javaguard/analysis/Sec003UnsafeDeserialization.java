package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.util.ArrayList;
import java.util.List;

/**
 * JG-SEC-003 (catalog &sect;1.2): several unsafe-deserialization shapes,
 * each distinctive enough by name alone to flag without symbol resolution.
 */
final class Sec003UnsafeDeserialization extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SEC-003", "Unsafe deserialization", Category.SECURITY, Severity.CRITICAL);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if ((name.equals("readObject") || name.equals("readUnshared")) && isType(call, "ObjectInputStream")
                    && !hasObjectInputFilter(call)) {
                out.add(finding(ctx, call, "ObjectInputStream." + name + "() is called without an object input filter.",
                        "Call setObjectInputFilter(...) before reading, or avoid native Java deserialization of untrusted data."));
            } else if (name.equals("readObject") && isType(call, "XMLDecoder")) {
                out.add(finding(ctx, call, "XMLDecoder.readObject() deserializes untrusted XML into arbitrary objects.",
                        "Avoid XMLDecoder for untrusted input; use a data format with a restricted, explicit schema."));
            } else if (name.equals("enableDefaultTyping") || name.equals("activateDefaultTyping")) {
                out.add(finding(ctx, call,
                        "Jackson default typing is enabled, allowing arbitrary classes to be instantiated from JSON.",
                        "Use a PolymorphicTypeValidator that allow-lists the expected base types, or avoid default typing."));
            }
        }
        for (ObjectCreationExpr creation : ctx.unit().findAll(ObjectCreationExpr.class)) {
            if (creation.getType().getNameAsString().equals("Yaml") && creation.getArguments().isEmpty()) {
                out.add(finding(ctx, creation,
                        "new Yaml() uses SnakeYAML's default constructor, which can instantiate arbitrary Java types from YAML.",
                        "Construct Yaml with a SafeConstructor (SnakeYAML < 2) or restrict allowed types via LoaderOptions (SnakeYAML >= 2)."));
            }
        }
        for (AnnotationExpr a : ctx.unit().findAll(AnnotationExpr.class)) {
            if (a.getNameAsString().equals("JsonTypeInfo") && a.toString().replace(" ", "").contains("Id.CLASS")) {
                out.add(finding(ctx, a, "@JsonTypeInfo(use = Id.CLASS) lets the JSON payload name the exact Java class to instantiate.",
                        "Use Id.NAME with a registered @JsonSubTypes allow-list instead of Id.CLASS."));
            }
        }
        return out;
    }

    private boolean isType(MethodCallExpr call, String type) {
        return DynamicStringBuilds.receiverTypeOf(call).filter(type::equals).isPresent();
    }

    private boolean hasObjectInputFilter(MethodCallExpr call) {
        return AstSupport.enclosingMethod(call).stream()
                .flatMap(m -> m.findAll(MethodCallExpr.class).stream())
                .anyMatch(c -> c.getNameAsString().equals("setObjectInputFilter"));
    }
}
