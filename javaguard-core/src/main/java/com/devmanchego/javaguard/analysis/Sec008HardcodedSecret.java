package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.analysis.secrets.SecretPatterns;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.ArrayList;
import java.util.List;

/**
 * JG-SEC-008 (catalog &sect;1.2): a hard-coded secret. Two independent
 * triggers, both delegated to {@link SecretPatterns} (shared verbatim with
 * handoff redaction, specification &sect;14.3): an identifier whose name looks
 * secret-shaped holding a non-trivial literal, or a literal matching a
 * well-known credential format regardless of name.
 *
 * <p>"On added lines only" (catalog wording) is not this check's job: it
 * reports every secret-shaped literal in the current file, and the
 * new-vs-baseline filter (specification &sect;9.3, Phase 4's
 * {@code ScopedAnalysis}) is what keeps only the ones the session introduced.
 */
final class Sec008HardcodedSecret extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SEC-008", "Hard-coded secret", Category.SECRET, Severity.BLOCKER);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public boolean mainOnly() {
        return false; // Catalog scope: all (MAJOR in tests).
    }

    @Override
    protected Severity severityFor(AstCheckContext ctx) {
        return ctx.testScope() ? Severity.MAJOR : Severity.BLOCKER;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (VariableDeclarator v : ctx.unit().findAll(VariableDeclarator.class)) {
            v.getInitializer().filter(Expression::isStringLiteralExpr).ifPresent(init -> {
                if (looksSecret(v.getNameAsString(), init.asStringLiteralExpr().asString())) {
                    out.add(nameBasedFinding(ctx, v, v.getNameAsString()));
                }
            });
        }
        for (AssignExpr a : ctx.unit().findAll(AssignExpr.class)) {
            String name = targetName(a);
            if (name != null && a.getValue().isStringLiteralExpr()
                    && looksSecret(name, a.getValue().asStringLiteralExpr().asString())) {
                out.add(nameBasedFinding(ctx, a, name));
            }
        }
        for (StringLiteralExpr literal : ctx.unit().findAll(StringLiteralExpr.class)) {
            for (SecretPatterns.Match match : SecretPatterns.findKnownFormats(literal.asString())) {
                out.add(finding(ctx, literal, "This literal matches the format of a " + match.kind() + ".",
                        "Remove the secret from source and load it from a secret manager, environment variable, or vault."));
            }
        }
        return out;
    }

    private Finding nameBasedFinding(AstCheckContext ctx, com.github.javaparser.ast.Node at, String name) {
        return finding(ctx, at, "'" + name + "' holds a hard-coded value that looks like a secret.",
                "Remove the secret from source and load it from a secret manager, environment variable, or vault.");
    }

    private boolean looksSecret(String name, String value) {
        return SecretPatterns.isSecretShaped(name, value);
    }

    private String targetName(AssignExpr a) {
        if (a.getTarget().isNameExpr()) {
            return a.getTarget().asNameExpr().getNameAsString();
        }
        if (a.getTarget().isFieldAccessExpr()) {
            return a.getTarget().asFieldAccessExpr().getNameAsString();
        }
        return null;
    }
}
