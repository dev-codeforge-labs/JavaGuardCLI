package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.model.Finding;
import com.github.javaparser.ast.Node;

import java.util.Map;
import java.util.Optional;

/**
 * Base for {@link AstCheck}s: turns {@link #info()} plus a node and a message
 * into a fully-formed {@link Finding}, so individual rules only decide *where*
 * to report and *what* to say.
 */
abstract class AbstractAstCheck implements AstCheck {

    protected Finding finding(AstCheckContext ctx, Node at, String message, String remediation) {
        return finding(ctx, at, at, message, remediation, true);
    }

    /** For the few rules whose severity varies per occurrence, not just per scope (catalog JG-SEC-005). */
    protected Finding findingWithSeverity(AstCheckContext ctx, Node at, com.devmanchego.javaguard.model.Severity severity,
                                          String message, String remediation) {
        RuleInfo info = info();
        int line = AstSupport.lineOf(at);
        Optional<String> snippet = AstSupport.snippet(ctx.lines(), line);
        return new Finding(info.ruleId(), info.title(), info.category(), severity, info.engine(),
                Optional.of(ctx.path()), line, at.getBegin().map(p -> p.column).orElse(0), AstSupport.endLineOf(at),
                message, remediation, snippet, Map.of(), Fingerprints.of(info.ruleId(), ctx.relativePath(), message));
    }

    /** Without a source snippet (e.g. the node's own text would not help, or spans too much). */
    protected Finding findingNoSnippet(AstCheckContext ctx, Node at, String message, String remediation) {
        return finding(ctx, at, at, message, remediation, false);
    }

    /** {@code snippetFrom} controls where the printed snippet starts (e.g. a containing statement). */
    protected Finding finding(AstCheckContext ctx, Node at, Node snippetFrom, String message, String remediation,
                              boolean includeSnippet) {
        RuleInfo info = info();
        int line = AstSupport.lineOf(at);
        int endLine = AstSupport.endLineOf(at);
        Optional<String> snippet = includeSnippet ? AstSupport.snippet(ctx.lines(), AstSupport.lineOf(snippetFrom))
                : Optional.empty();
        return new Finding(info.ruleId(), info.title(), info.category(), severityFor(ctx), info.engine(),
                Optional.of(ctx.path()), line, at.getBegin().map(p -> p.column).orElse(0), endLine, message,
                remediation, snippet, Map.of(), Fingerprints.of(info.ruleId(), ctx.relativePath(), message));
    }

    /** Overridden by the few rules whose severity depends on scope (catalog JG-SEC-008). */
    protected com.devmanchego.javaguard.model.Severity severityFor(AstCheckContext ctx) {
        return info().defaultSeverity();
    }
}
