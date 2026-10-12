package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-RES-003 (catalog &sect;1.11): a call to a remote system - {@code RestTemplate}, {@code WebClient}, {@code HttpClient},
 * a messaging template, any {@code *Client} - inside a {@code @Transactional} method or class. The database connection is
 * held, idle, for as long as the remote call takes; under load the pool runs dry and the whole application stalls. A method
 * that opts out with {@code NOT_SUPPORTED}/{@code NEVER} is not reported.
 */
final class Res003RemoteCallInTransaction extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-RES-003", "Remote call inside a transaction", Category.RELIABILITY, Severity.MAJOR);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public Set<Framework> requiresAnyOf() {
        return Frameworks.SPRING;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            if (ResSupport.isRemoteCall(call) && ResSupport.inTransaction(call)) {
                out.add(finding(ctx, call, ResSupport.describe(call) + " is a remote call made inside a transaction: the database connection "
                        + "stays checked out while it runs.",
                        "Make the remote call before or after the transactional method, or publish an event/outbox record and call from outside."));
            }
        }
        return out;
    }
}
