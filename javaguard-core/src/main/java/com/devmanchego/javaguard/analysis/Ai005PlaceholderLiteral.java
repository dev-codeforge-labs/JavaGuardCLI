package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-AI-005 (catalog &sect;1.8): string literals like {@code "TODO"},
 * {@code "changeme"}, {@code "foo"}/{@code "bar"}, lorem-ipsum filler,
 * {@code example.com} URLs, {@code "your-api-key"} or {@code "xxx"} are
 * placeholders an agent left behind instead of real values - out of place in
 * non-test code, where "foo"/"bar" are also legitimate, common test fixtures.
 */
final class Ai005PlaceholderLiteral extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-AI-005", "Placeholder literal", Category.AI_DEBRIS, Severity.MAJOR);

    private static final Set<String> EXACT_PLACEHOLDERS = Set.of(
            "todo", "fixme", "changeme", "foo", "bar", "xxx", "your-api-key", "your_api_key");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        if (ctx.testScope()) {
            return out;
        }
        for (StringLiteralExpr lit : ctx.unit().findAll(StringLiteralExpr.class)) {
            String value = lit.asString();
            if (isPlaceholder(value)) {
                out.add(finding(ctx, lit, "The string literal \"" + value + "\" looks like a placeholder value left behind.",
                        "Replace it with the real value, or load it from configuration."));
            }
        }
        return out;
    }

    private boolean isPlaceholder(String value) {
        String lower = value.toLowerCase();
        return EXACT_PLACEHOLDERS.contains(lower) || lower.contains("lorem ipsum") || lower.contains("example.com");
    }
}
