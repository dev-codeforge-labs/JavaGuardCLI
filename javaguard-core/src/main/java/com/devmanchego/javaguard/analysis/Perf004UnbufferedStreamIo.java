package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-PERF-004 (catalog &sect;1.3): a single-byte {@code read()} in a loop on
 * a raw (unbuffered) {@code FileInputStream}/{@code FileReader}. Every call
 * becomes a system call.
 *
 * <p>Scoped to the zero-argument {@code read()} overload only: the write-side
 * ({@code write(int)}) shares its argument count with {@code write(byte[])},
 * which is already efficient, and cannot be told apart without symbol
 * resolution - flagging it would risk exactly the false positives this
 * project prioritizes avoiding.
 */
final class Perf004UnbufferedStreamIo extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-PERF-004", "Unbuffered stream I/O", Category.PERFORMANCE, Severity.MAJOR);

    private static final Set<String> RAW_READ_TYPES = Set.of("FileInputStream", "FileReader");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals("read") || !call.getArguments().isEmpty() || !AstSupport.isInLoop(call)) {
                continue;
            }
            call.getScope().filter(Expression::isNameExpr).map(Expression::asNameExpr)
                    .flatMap(AstSupport::declaredTypeSimpleName)
                    .filter(RAW_READ_TYPES::contains)
                    .ifPresent(type -> out.add(finding(ctx, call,
                            type + ".read() reads a single byte/char per call inside a loop.",
                            "Wrap it in a Buffered" + (type.equals("FileReader") ? "Reader" : "InputStream")
                                    + ", or read into an array/buffer instead of one unit at a time.")));
        }
        return out;
    }
}
