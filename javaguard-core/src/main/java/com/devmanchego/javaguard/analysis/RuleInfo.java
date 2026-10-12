package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Severity;

import java.util.Objects;

/**
 * Static metadata for one rule (catalog columns): identity, category, default
 * severity, producing engine, and which gate it runs in. Independent of any
 * particular finding it produces.
 *
 * @param gate {@code "F"} (post-edit and end-of-turn) or {@code "E"} (end-of-turn
 *             only), catalog notation
 */
public record RuleInfo(String ruleId, String title, Category category, Severity defaultSeverity, String engine,
                       String gate) {

    public RuleInfo {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(defaultSeverity, "defaultSeverity");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(gate, "gate");
    }

    public static RuleInfo ast(String ruleId, String title, Category category, Severity defaultSeverity) {
        return new RuleInfo(ruleId, title, category, defaultSeverity, "ast", "F");
    }
}
