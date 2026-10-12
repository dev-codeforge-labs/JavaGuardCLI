package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.change.ScopedAnalysis;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.java.JavaLanguageModule;
import net.sourceforge.pmd.lang.rule.RulePriority;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs PMD 7 in-process against one file with the curated
 * {@code javaguard-ruleset.xml} (catalog &sect;3): only the listed rules run,
 * and each violation's severity/category comes from the catalog's override
 * table below, not PMD's own priority (used only as a fallback for a rule
 * this table has not been updated for).
 *
 * <p>Findings use id {@code PMD:<RuleName>} and engine {@code pmd}; merging
 * them with the AST engine's findings under the overlapping JG id (catalog
 * &sect;4 deduplication) is the caller's job, not this engine's - it only
 * reports what PMD found.
 */
public final class PmdEngine implements ScopedAnalysis.FileAnalyzer {

    /** Bumped whenever the ruleset or the severity/category table changes; part of the baseline cache key. */
    public static final String ANALYZER_KEY = "pmd-v1";

    private static final String RULESET_RESOURCE = "pmd/javaguard-ruleset.xml";

    /**
     * The curated rule table (catalog &sect;3), one source of truth for both
     * the severity/category override applied to violations and the
     * {@code javaguard rules} listing ({@link #RULES}).
     */
    private static final List<RuleInfo> CURATED_RULES = List.of(
            pmdRule("EmptyCatchBlock", Category.RELIABILITY, Severity.MAJOR),
            pmdRule("AvoidCatchingNPE", Category.RELIABILITY, Severity.MAJOR),
            pmdRule("ReturnFromFinallyBlock", Category.RELIABILITY, Severity.CRITICAL),
            pmdRule("CloseResource", Category.RELIABILITY, Severity.CRITICAL),
            pmdRule("CompareObjectsWithEquals", Category.RELIABILITY, Severity.MAJOR),
            pmdRule("UseEqualsToCompareStrings", Category.RELIABILITY, Severity.CRITICAL),
            pmdRule("AvoidBranchingStatementAsLastInLoop", Category.RELIABILITY, Severity.MAJOR),
            pmdRule("ImplicitSwitchFallThrough", Category.RELIABILITY, Severity.MAJOR),
            pmdRule("AvoidDecimalLiteralsInBigDecimalConstructor", Category.RELIABILITY, Severity.MAJOR),
            pmdRule("ProperCloneImplementation", Category.RELIABILITY, Severity.MAJOR),
            pmdRule("SingleMethodSingleton", Category.CONCURRENCY, Severity.MAJOR),
            pmdRule("AvoidInstanceofChecksInCatchClause", Category.RELIABILITY, Severity.INFO),
            pmdRule("PreserveStackTrace", Category.RELIABILITY, Severity.MAJOR),
            pmdRule("AvoidReassigningLoopVariables", Category.RELIABILITY, Severity.INFO),
            pmdRule("DoubleCheckedLocking", Category.CONCURRENCY, Severity.CRITICAL),
            pmdRule("NonThreadSafeSingleton", Category.CONCURRENCY, Severity.MAJOR),
            pmdRule("UnsynchronizedStaticFormatter", Category.CONCURRENCY, Severity.CRITICAL),
            pmdRule("DontCallThreadRun", Category.CONCURRENCY, Severity.MAJOR),
            pmdRule("UseStringBufferForStringAppends", Category.PERFORMANCE, Severity.MAJOR),
            pmdRule("InefficientStringBuffering", Category.PERFORMANCE, Severity.INFO),
            pmdRule("BigIntegerInstantiation", Category.PERFORMANCE, Severity.INFO),
            pmdRule("AddEmptyString", Category.PERFORMANCE, Severity.INFO),
            pmdRule("HardCodedCryptoKey", Category.SECURITY, Severity.CRITICAL),
            pmdRule("InsecureCryptoIv", Category.SECURITY, Severity.CRITICAL));

    /** Metadata for every curated PMD rule, id {@code PMD:<RuleName>}, for the {@code javaguard rules} listing. */
    public static final List<RuleInfo> RULES = CURATED_RULES;

    private static final Map<String, Severity> SEVERITY_OVERRIDE = CURATED_RULES.stream()
            .collect(java.util.stream.Collectors.toMap(r -> r.ruleId().substring("PMD:".length()), RuleInfo::defaultSeverity));

    private static final Map<String, Category> CATEGORY_OVERRIDE = CURATED_RULES.stream()
            .collect(java.util.stream.Collectors.toMap(r -> r.ruleId().substring("PMD:".length()), RuleInfo::category));

    private static RuleInfo pmdRule(String name, Category category, Severity severity) {
        return new RuleInfo("PMD:" + name, name, category, severity, "pmd", "F");
    }

    @Override
    public List<Finding> analyze(String relativePath, String content) {
        PMDConfiguration configuration = new PMDConfiguration();
        configuration.addRuleSet(RULESET_RESOURCE);
        configuration.setThreads(0);
        configuration.setDefaultLanguageVersion(JavaLanguageModule.getInstance().getVersion("21"));
        configuration.setIgnoreIncrementalAnalysis(true);

        List<Finding> out = new ArrayList<>();
        try (PmdAnalysis pmd = PmdAnalysis.create(configuration)) {
            pmd.files().addSourceFile(FileId.fromPathLikeString(relativePath), content);
            Report report = pmd.performAnalysisAndCollectReport();
            for (RuleViolation violation : report.getViolations()) {
                out.add(toFinding(relativePath, violation));
            }
        }
        return out;
    }

    private Finding toFinding(String relativePath, RuleViolation violation) {
        String ruleName = violation.getRule().getName();
        String ruleId = "PMD:" + ruleName;
        Severity severity = SEVERITY_OVERRIDE.getOrDefault(ruleName, fromPriority(violation.getRule().getPriority()));
        Category category = CATEGORY_OVERRIDE.getOrDefault(ruleName, Category.RELIABILITY);
        String message = violation.getDescription();
        return new Finding(ruleId, ruleName, category, severity, "pmd", Optional.of(Path.of(relativePath)),
                violation.getBeginLine(), violation.getBeginColumn(), violation.getEndLine(), message,
                "See the PMD rule documentation for '" + ruleName + "'.", Optional.empty(), Map.of(),
                Fingerprints.of(ruleId, relativePath, message));
    }

    private Severity fromPriority(RulePriority priority) {
        return switch (priority.getPriority()) {
            case 1 -> Severity.CRITICAL;
            case 2 -> Severity.MAJOR;
            default -> Severity.INFO;
        };
    }
}
