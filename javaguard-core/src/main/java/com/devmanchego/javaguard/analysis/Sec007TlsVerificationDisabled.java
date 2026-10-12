package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-SEC-007 (catalog &sect;1.2): TLS verification effectively turned off, in
 * any of its common shapes: an empty/no-op {@code X509TrustManager.checkServerTrusted},
 * a hostname verifier that always returns {@code true}, or a reference to one
 * of the well-known "trust everything" helper classes.
 */
final class Sec007TlsVerificationDisabled extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SEC-007", "TLS verification disabled", Category.SECURITY, Severity.BLOCKER);

    private static final Set<String> SUSPICIOUS_NAMES = Set.of(
            "NoopHostnameVerifier", "TrustAllStrategy", "TrustSelfSignedStrategy",
            "ALLOW_ALL_HOSTNAME_VERIFIER", "InsecureTrustManagerFactory");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (ObjectCreationExpr creation : ctx.unit().findAll(ObjectCreationExpr.class)) {
            if (creation.getType().getNameAsString().equals("X509TrustManager") && creation.getAnonymousClassBody().isPresent()) {
                checkTrustManagerMembers(ctx, creation, creation.getAnonymousClassBody().get(), out);
            }
        }
        for (ClassOrInterfaceDeclaration c : ctx.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (implementsX509TrustManager(c)) {
                checkTrustManagerMembers(ctx, c, c.getMembers(), out);
            }
        }
        for (LambdaExpr lambda : ctx.unit().findAll(LambdaExpr.class)) {
            if (lambda.getParameters().size() == 2 && alwaysReturnsTrue(lambda)) {
                out.add(finding(ctx, lambda, "A 2-argument lambda unconditionally returns true; this shape matches a "
                        + "HostnameVerifier that accepts every host.",
                        "Verify the hostname actually matches the certificate; do not accept every connection."));
            }
        }
        for (NameExpr n : ctx.unit().findAll(NameExpr.class)) {
            if (SUSPICIOUS_NAMES.contains(n.getNameAsString())) {
                out.add(suspiciousFinding(ctx, n, n.getNameAsString()));
            }
        }
        for (FieldAccessExpr f : ctx.unit().findAll(FieldAccessExpr.class)) {
            if (SUSPICIOUS_NAMES.contains(f.getNameAsString())) {
                out.add(suspiciousFinding(ctx, f, f.getNameAsString()));
            }
        }
        for (ClassOrInterfaceType t : ctx.unit().findAll(ClassOrInterfaceType.class)) {
            if (SUSPICIOUS_NAMES.contains(t.getNameAsString())) {
                out.add(suspiciousFinding(ctx, t, t.getNameAsString()));
            }
        }
        return out;
    }

    private Finding suspiciousFinding(AstCheckContext ctx, com.github.javaparser.ast.Node at, String name) {
        return finding(ctx, at, "'" + name + "' disables TLS hostname/certificate verification.",
                "Use the platform's default trust manager and hostname verifier; do not trust every certificate/host.");
    }

    private boolean implementsX509TrustManager(ClassOrInterfaceDeclaration c) {
        return c.getImplementedTypes().stream().anyMatch(t -> t.getNameAsString().equals("X509TrustManager"));
    }

    private void checkTrustManagerMembers(AstCheckContext ctx, com.github.javaparser.ast.Node at,
                                          NodeList<BodyDeclaration<?>> members, List<Finding> out) {
        for (BodyDeclaration<?> member : members) {
            if (member.isMethodDeclaration()) {
                MethodDeclaration m = member.asMethodDeclaration();
                if (m.getNameAsString().equals("checkServerTrusted") && isEmptyOrBareReturn(m.getBody().orElse(null))) {
                    out.add(finding(ctx, m, "checkServerTrusted() does not actually check anything.",
                            "Delegate to the platform's default X509TrustManager, or perform a real chain/hostname check."));
                }
            }
        }
    }

    private boolean isEmptyOrBareReturn(BlockStmt body) {
        if (body == null) {
            return false;
        }
        List<Statement> statements = body.getStatements();
        if (statements.isEmpty()) {
            return true;
        }
        return statements.size() == 1 && statements.get(0).isReturnStmt()
                && statements.get(0).asReturnStmt().getExpression().isEmpty();
    }

    private boolean alwaysReturnsTrue(LambdaExpr lambda) {
        if (lambda.getExpressionBody().isPresent()) {
            return isLiteralTrue(lambda.getExpressionBody().get());
        }
        if (lambda.getBody().isBlockStmt()) {
            List<Statement> statements = lambda.getBody().asBlockStmt().getStatements();
            return statements.size() == 1 && statements.get(0).isReturnStmt()
                    && statements.get(0).asReturnStmt().getExpression().filter(this::isLiteralTrue).isPresent();
        }
        return false;
    }

    private boolean isLiteralTrue(Expression e) {
        return e.isBooleanLiteralExpr() && e.asBooleanLiteralExpr().getValue();
    }
}
