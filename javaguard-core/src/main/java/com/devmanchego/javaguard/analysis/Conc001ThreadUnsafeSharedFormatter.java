package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-CONC-001 (catalog &sect;1.5): {@code SimpleDateFormat}, {@code DateFormat},
 * {@code DecimalFormat} and {@code MessageFormat} are not thread-safe; sharing
 * one as a {@code static} field, or as an instance field of a singleton bean,
 * corrupts state under concurrent use.
 */
final class Conc001ThreadUnsafeSharedFormatter extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-CONC-001", "Thread-unsafe shared formatter", Category.CONCURRENCY, Severity.CRITICAL);

    private static final Set<String> UNSAFE_FORMATTER_TYPES = Set.of(
            "SimpleDateFormat", "DateFormat", "DecimalFormat", "MessageFormat");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (FieldDeclaration f : ctx.unit().findAll(FieldDeclaration.class)) {
            if (!UNSAFE_FORMATTER_TYPES.contains(AstSupport.simpleName(f.getElementType().asString()))) {
                continue;
            }
            boolean isStatic = f.hasModifier(Modifier.Keyword.STATIC);
            boolean sharedInstanceField = AstSupport.enclosingClass(f).filter(AstSupport::isSingletonBean).isPresent();
            if (!isStatic && !sharedInstanceField) {
                continue;
            }
            for (VariableDeclarator v : f.getVariables()) {
                out.add(finding(ctx, v,
                        "'" + v.getNameAsString() + "' is a shared " + AstSupport.simpleName(f.getElementType().asString())
                                + ", which is not thread-safe.",
                        "Use a ThreadLocal<" + AstSupport.simpleName(f.getElementType().asString())
                                + ">, DateTimeFormatter (immutable, thread-safe), or create one per use."));
            }
        }
        return out;
    }
}
