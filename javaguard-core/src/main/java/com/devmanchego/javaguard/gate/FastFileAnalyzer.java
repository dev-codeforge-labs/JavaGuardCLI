package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.analysis.ArchitectureCheck;
import com.devmanchego.javaguard.analysis.AstCheck;
import com.devmanchego.javaguard.analysis.AstCheckEngine;
import com.devmanchego.javaguard.analysis.EntityIndex;
import com.devmanchego.javaguard.analysis.FindingMerge;
import com.devmanchego.javaguard.analysis.PmdEngine;
import com.devmanchego.javaguard.analysis.syntax.SynCheck;
import com.devmanchego.javaguard.change.ScopedAnalysis;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.project.Framework;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * File-local checks of the fast gate (specification &sect;10.2 steps 2-4): parse, AST rules (security,
 * reliability, performance, concurrency, AI debris, secrets, test integrity, and the Spring/JPA rules when the
 * module's frameworks call for them), the configured architecture rules, and the curated PMD ruleset, merged per
 * catalog &sect;4. A syntax error stops the file here: nothing else can be analyzed on source that does not parse.
 *
 * <p>Used for both the current content and the baseline, so the same instance must give the same answer for the
 * same input; {@link #key()} identifies its configuration for the baseline-findings cache. One instance serves
 * one module, because the detected frameworks and the entity index are per module.
 */
public final class FastFileAnalyzer implements ScopedAnalysis.FileAnalyzer {

    private static final Logger LOG = Logger.getLogger(FastFileAnalyzer.class.getName());

    private final AstCheckEngine ast;
    private final PmdEngine pmd;

    /** All framework-gated rules active, no architecture rules, no entity index: tools without a module. */
    public FastFileAnalyzer(boolean pmdEnabled) {
        this(pmdEnabled, EnumSet.allOf(Framework.class), EntityIndex.empty(), List.of());
    }

    /**
     * @param frameworks  detected on the module; Spring/JPA rules run only when theirs are present
     * @param entities    the module's JPA entities, for rules that need types declared in other files
     * @param archRules   {@code arch.rules} of the configuration (JG-ARCH-001); empty means none
     */
    public FastFileAnalyzer(boolean pmdEnabled, Set<Framework> frameworks, EntityIndex entities,
                            List<JavaGuardConfig.ArchRule> archRules) {
        List<AstCheck> extra = new ArrayList<>();
        if (!archRules.isEmpty()) {
            extra.add(new ArchitectureCheck(archRules));
        }
        this.ast = new AstCheckEngine(frameworks, entities, extra);
        this.pmd = pmdEnabled ? new PmdEngine() : null;
    }

    @Override
    public List<Finding> analyze(String relativePath, String content) {
        List<Finding> astFindings = ast.analyze(relativePath, content);
        if (pmd == null || astFindings.stream().anyMatch(f -> SynCheck.RULE_ID.equals(f.ruleId()))) {
            return astFindings;
        }
        try {
            return FindingMerge.merge(astFindings, pmd.analyze(relativePath, content));
        } catch (RuntimeException e) {
            // A PMD failure must not hide the AST findings, and must not fail the gate.
            LOG.log(Level.WARNING, "PMD failed on " + relativePath + "; reporting AST findings only.", e);
            return astFindings;
        }
    }

    /** Identity of this analyzer configuration, for the baseline-findings cache key. */
    public String key() {
        return pmd == null ? ast.key() : ast.key() + "+" + PmdEngine.ANALYZER_KEY;
    }
}
