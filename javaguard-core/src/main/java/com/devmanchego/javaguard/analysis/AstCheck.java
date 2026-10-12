package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Finding;

import java.util.List;

/**
 * One syntactic rule over a parsed Java file (catalog &sect;1). Stateless and
 * thread-safe; {@link AstCheckEngine} parses the file once and runs every
 * registered check against the same {@link AstCheckContext}.
 *
 * <p>Checks are deliberately syntactic (no symbol resolution, no classpath):
 * that is what keeps the fast gate fast (specification &sect;2 decision D5,
 * &sect;20) and is why each one exists only where a mature engine does not
 * already cover the case with good precision.
 */
public interface AstCheck {

    /**
     * Frameworks of which at least one must be detected on the module for this rule to run (specification
     * &sect;11.2: the Spring/JPA rules are "active only when detected"). Empty means always active.
     */
    default java.util.Set<com.devmanchego.javaguard.project.Framework> requiresAnyOf() {
        return java.util.Set.of();
    }

    /**
     * Whether the rule applies to production code only (catalog scope {@code main}, the default): the engine does not run
     * it on test sources. Rules whose catalog scope is {@code all} or {@code test} override this.
     */
    default boolean mainOnly() {
        return true;
    }

    RuleInfo info();

    List<Finding> check(AstCheckContext ctx);
}
