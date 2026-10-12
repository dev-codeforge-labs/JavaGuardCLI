package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * JG-SEC-005 (catalog &sect;1.2): weak digests, weak ciphers, undersized RSA
 * keys, and constant IVs. Only the MD5/SHA-1 case has a severity exception
 * (INFO when the name suggests a checksum/cache key rather than a security
 * hash); everything else is always the rule's default severity.
 */
final class Sec005WeakCryptography extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SEC-005", "Weak cryptography", Category.SECURITY, Severity.CRITICAL);

    private static final Set<String> WEAK_DIGESTS = Set.of("MD5", "SHA-1", "SHA1");
    private static final Set<String> WEAK_CIPHERS = Set.of("DES", "DESEDE", "RC2", "RC4", "BLOWFISH");
    private static final Pattern NON_SECURITY_NAME = Pattern.compile("(?i).*(checksum|etag|cache).*");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals("getInstance") || call.getArguments().isEmpty()) {
                continue;
            }
            String receiverType = call.getScope().filter(Expression::isNameExpr)
                    .map(s -> s.asNameExpr().getNameAsString()).orElse("");
            Expression algArg = call.getArgument(0);
            if (!algArg.isStringLiteralExpr()) {
                continue;
            }
            String alg = algArg.asStringLiteralExpr().asString();
            if (receiverType.equals("MessageDigest") && WEAK_DIGESTS.contains(alg.toUpperCase(Locale.ROOT))) {
                out.add(digestFinding(ctx, call, alg));
            } else if (receiverType.equals("Cipher") && isWeakCipherSpec(alg)) {
                out.add(finding(ctx, call, "Cipher.getInstance(\"" + alg + "\") uses a weak or under-specified algorithm/mode.",
                        "Use a strong, explicit transformation, e.g. \"AES/GCM/NoPadding\"."));
            } else if (receiverType.equals("KeyPairGenerator") && alg.equalsIgnoreCase("RSA")) {
                checkRsaKeySize(ctx, call, out);
            }
        }
        for (ObjectCreationExpr creation : ctx.unit().findAll(ObjectCreationExpr.class)) {
            if (creation.getType().getNameAsString().equals("IvParameterSpec") && !creation.getArguments().isEmpty()
                    && looksLikeConstantBytes(creation.getArguments().get(0))) {
                out.add(finding(ctx, creation, "IvParameterSpec is built from a constant value.",
                        "Generate a fresh random IV for every encryption operation; never reuse or hard-code one."));
            }
        }
        return out;
    }

    private Finding digestFinding(AstCheckContext ctx, MethodCallExpr call, String alg) {
        String context = enclosingIdentifierName(call);
        Severity severity = NON_SECURITY_NAME.matcher(context).matches() ? Severity.INFO : Severity.CRITICAL;
        return findingWithSeverity(ctx, call, severity, "MessageDigest.getInstance(\"" + alg + "\") is a broken hash for security purposes.",
                "Use SHA-256 or stronger for anything security-sensitive (passwords need a slow hash like bcrypt/Argon2, not a raw digest).");
    }

    /** The variable/field name this call's result feeds, plus the enclosing method's name: either may signal intent. */
    private String enclosingIdentifierName(MethodCallExpr call) {
        String varName = call.findAncestor(VariableDeclarator.class).map(VariableDeclarator::getNameAsString).orElse("");
        String methodName = AstSupport.enclosingMethod(call).map(m -> m.getNameAsString()).orElse("");
        return varName + " " + methodName;
    }

    private boolean isWeakCipherSpec(String transformation) {
        String upper = transformation.toUpperCase(Locale.ROOT);
        String algorithmPart = upper.split("/", 2)[0];
        return WEAK_CIPHERS.contains(algorithmPart) || upper.contains("/ECB/") || upper.equals("AES");
    }

    private void checkRsaKeySize(AstCheckContext ctx, MethodCallExpr getInstanceCall, List<Finding> out) {
        String varName = getInstanceCall.findAncestor(VariableDeclarator.class).map(VariableDeclarator::getNameAsString).orElse(null);
        if (varName == null) {
            return;
        }
        AstSupport.enclosingMethod(getInstanceCall).stream()
                .flatMap(m -> m.findAll(MethodCallExpr.class).stream())
                .filter(c -> c.getNameAsString().equals("initialize"))
                .filter(c -> c.getScope().filter(s -> s.isNameExpr() && s.asNameExpr().getNameAsString().equals(varName)).isPresent())
                .filter(c -> !c.getArguments().isEmpty() && c.getArgument(0).isIntegerLiteralExpr())
                .forEach(c -> {
                    int bits = c.getArgument(0).asIntegerLiteralExpr().asNumber().intValue();
                    if (bits < 2048) {
                        out.add(finding(ctx, c, "RSA key size " + bits + " is below the recommended minimum of 2048 bits.",
                                "Initialize the KeyPairGenerator with at least 2048 bits (3072+ for longer-lived keys)."));
                    }
                });
    }

    private boolean looksLikeConstantBytes(Expression e) {
        return e.isArrayCreationExpr() || e.isArrayInitializerExpr() || AstSupport.looksConstant(e)
                || (e instanceof StringLiteralExpr);
    }
}
