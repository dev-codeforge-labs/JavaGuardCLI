package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * JG-REL-009 (catalog &sect;1.4): a {@code double}/{@code float} field,
 * parameter, or local named like a monetary amount - binary floating point
 * cannot represent most decimal fractions exactly.
 */
final class Rel009FloatingPointForMoney extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-REL-009", "Floating point for money", Category.RELIABILITY, Severity.MAJOR);

    private static final Set<String> FLOATING_TYPES = Set.of("double", "float", "Double", "Float");
    private static final Pattern MONEY_NAME = Pattern.compile(
            "(?i).*(amount|price|balance|cost|total|fee|tax|salary|payment|money).*");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (FieldDeclaration f : ctx.unit().findAll(FieldDeclaration.class)) {
            if (FLOATING_TYPES.contains(f.getElementType().asString())) {
                for (VariableDeclarator v : f.getVariables()) {
                    if (MONEY_NAME.matcher(v.getNameAsString()).matches()) {
                        out.add(moneyFinding(ctx, v, v.getNameAsString()));
                    }
                }
            }
        }
        for (Parameter p : ctx.unit().findAll(Parameter.class)) {
            if (FLOATING_TYPES.contains(p.getTypeAsString()) && MONEY_NAME.matcher(p.getNameAsString()).matches()) {
                out.add(moneyFinding(ctx, p, p.getNameAsString()));
            }
        }
        for (VariableDeclarator v : ctx.unit().findAll(VariableDeclarator.class)) {
            if (v.getParentNode().filter(p -> p instanceof FieldDeclaration).isPresent()) {
                continue; // already handled above
            }
            if (FLOATING_TYPES.contains(AstSupport.simpleName(v.getTypeAsString())) && MONEY_NAME.matcher(v.getNameAsString()).matches()) {
                out.add(moneyFinding(ctx, v, v.getNameAsString()));
            }
        }
        return out;
    }

    private Finding moneyFinding(AstCheckContext ctx, com.github.javaparser.ast.Node at, String name) {
        return finding(ctx, at, "'" + name + "' looks like a monetary value but is declared as a binary floating-point type.",
                "Use BigDecimal (or a fixed-point integer, e.g. cents as a long) for money; floating point cannot represent most decimal fractions exactly.");
    }
}
