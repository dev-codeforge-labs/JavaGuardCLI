package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * JG-AI-003 (catalog &sect;1.8): a method body that only throws
 * {@code UnsupportedOperationException}/a "not implemented"-flavored
 * {@code RuntimeException}, or only returns {@code null}/a default value next
 * to a TODO/placeholder/stub comment, is an agent that gave up mid-task
 * rather than an intentional abstract-method placeholder. A bare
 * {@code // TODO implement ...}-style comment anywhere is the same debris at
 * a lower severity.
 */
final class Ai003UnimplementedStub extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-AI-003", "Unimplemented stub", Category.AI_DEBRIS, Severity.CRITICAL);

    private static final Pattern TODO_IMPLEMENT = Pattern.compile("(?i)todo:?\\s*(implement|add|complete|finish)");
    private static final Pattern STUB_MARKER = Pattern.compile("(?i)todo|placeholder|stub");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodDeclaration m : ctx.unit().findAll(MethodDeclaration.class)) {
            m.getBody().ifPresent(body -> checkStubBody(ctx, m, body, out));
        }
        for (Comment c : ctx.comments()) {
            if (TODO_IMPLEMENT.matcher(c.getContent()).find()) {
                out.add(findingWithSeverity(ctx, c, Severity.MAJOR, "A 'TODO implement' style comment marks unfinished work.",
                        "Finish the implementation, or track it in an issue tracker instead of leaving a TODO in the code."));
            }
        }
        return out;
    }

    private void checkStubBody(AstCheckContext ctx, MethodDeclaration m, BlockStmt body, List<Finding> out) {
        List<Statement> statements = body.getStatements();
        if (statements.size() != 1) {
            return;
        }
        Statement only = statements.get(0);
        if (only instanceof ThrowStmt throwStmt && isUnimplementedThrow(throwStmt)) {
            out.add(finding(ctx, m, "Method '" + m.getNameAsString() + "' only throws an 'unimplemented' exception.",
                    "Implement the method, or if it is intentionally abstract, document why."));
        } else if (only instanceof ReturnStmt returnStmt && isDefaultReturn(returnStmt) && hasStubComment(ctx, m)) {
            out.add(finding(ctx, m, "Method '" + m.getNameAsString() + "' only returns a default value next to a stub marker comment.",
                    "Implement the method body instead of returning a placeholder default."));
        }
    }

    private boolean isUnimplementedThrow(ThrowStmt throwStmt) {
        if (!(throwStmt.getExpression() instanceof ObjectCreationExpr creation)) {
            return false;
        }
        String type = creation.getType().getNameAsString();
        if (type.equals("UnsupportedOperationException")) {
            return true;
        }
        if (!type.equals("RuntimeException") || creation.getArguments().isEmpty()) {
            return false;
        }
        Expression arg = creation.getArgument(0);
        if (!arg.isStringLiteralExpr()) {
            return false;
        }
        String message = arg.asStringLiteralExpr().asString().toLowerCase();
        return message.contains("not implemented") || message.contains("todo");
    }

    private boolean isDefaultReturn(ReturnStmt returnStmt) {
        return returnStmt.getExpression().filter(this::isDefaultLiteral).isPresent();
    }

    private boolean isDefaultLiteral(Expression e) {
        if (e.isNullLiteralExpr() || e.isBooleanLiteralExpr()) {
            return true;
        }
        if (e.isIntegerLiteralExpr()) {
            return e.asIntegerLiteralExpr().asNumber().intValue() == 0;
        }
        if (e.isStringLiteralExpr()) {
            return e.asStringLiteralExpr().asString().isEmpty();
        }
        return false;
    }

    /** Any lexed comment (comments are unattached, see {@link AstCheckContext}) within the method's line span. */
    private boolean hasStubComment(AstCheckContext ctx, MethodDeclaration m) {
        int from = AstSupport.lineOf(m);
        int to = AstSupport.endLineOf(m);
        return ctx.comments().stream()
                .filter(c -> c.getBegin().isPresent())
                .filter(c -> c.getBegin().get().line >= from && c.getBegin().get().line <= to)
                .anyMatch(c -> STUB_MARKER.matcher(c.getContent()).find());
    }
}
