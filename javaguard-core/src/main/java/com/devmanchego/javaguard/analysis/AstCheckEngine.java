package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.analysis.syntax.SynCheck;
import com.devmanchego.javaguard.change.ScopedAnalysis;
import com.devmanchego.javaguard.model.Finding;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Runs every registered {@link AstCheck} against one file (specification
 * &sect;10.2 step 2-3): parse once with JavaParser, hand the same
 * {@link AstCheckContext} to each check, collect findings.
 *
 * <p>A parse failure short-circuits to a single {@link SynCheck#RULE_ID}
 * finding, matching &sect;10.2 step 2 ("Remaining steps are skipped for that
 * file"); a check that throws is treated as silent (logged, not fatal) so one
 * bad rule can never take the others down with it.
 *
 * <p>Test sources run the same checks as main sources (only severities differ,
 * e.g. JG-SEC-008): test-only rules (test integrity, catalog &sect;1.7) are
 * Phase 10 and not part of this engine.
 */
public final class AstCheckEngine implements ScopedAnalysis.FileAnalyzer {

    /** Bumped whenever a check is added, removed or changes behavior; part of the baseline cache key. */
    public static final String ANALYZER_KEY = "ast-v7";

    private final List<AstCheck> checks;
    private final Predicate<String> testScope;
    private final java.util.Set<com.devmanchego.javaguard.project.Framework> frameworks;
    private final EntityIndex entities;
    private final java.util.logging.Logger log = java.util.logging.Logger.getLogger(AstCheckEngine.class.getName());

    /** Every framework-gated rule active, no module entity index: for tests and tools without a module. */
    public AstCheckEngine() {
        this(CheckRegistry.ALL, AstCheckEngine::looksLikeTestSource,
                java.util.EnumSet.allOf(com.devmanchego.javaguard.project.Framework.class), EntityIndex.empty());
    }

    /**
     * The engine for one module: framework-gated rules run only when one of their frameworks is detected, and
     * {@code extraChecks} (configuration-driven rules such as JG-ARCH-001) run alongside the registry.
     */
    public AstCheckEngine(java.util.Set<com.devmanchego.javaguard.project.Framework> frameworks, EntityIndex entities,
                          List<AstCheck> extraChecks) {
        this(Stream.concat(CheckRegistry.ALL.stream(), extraChecks.stream()).toList(), AstCheckEngine::looksLikeTestSource,
                frameworks, entities);
    }

    AstCheckEngine(List<AstCheck> checks, Predicate<String> testScope) {
        this(checks, testScope, java.util.EnumSet.allOf(com.devmanchego.javaguard.project.Framework.class), EntityIndex.empty());
    }

    AstCheckEngine(List<AstCheck> checks, Predicate<String> testScope,
                   java.util.Set<com.devmanchego.javaguard.project.Framework> frameworks, EntityIndex entities) {
        this.checks = List.copyOf(checks);
        this.testScope = testScope;
        this.frameworks = java.util.Set.copyOf(frameworks);
        this.entities = entities;
    }

    /**
     * Identity of this engine's behavior for the baseline-findings cache: the rule set version, which gated
     * rules are active, and the configured extra checks.
     */
    public String key() {
        String active = checks.stream().filter(this::active)
                .map(c -> c.info().ruleId() + (c instanceof ArchitectureCheck a ? "{" + a.signature() + "}" : ""))
                .sorted().collect(java.util.stream.Collectors.joining(","));
        return ANALYZER_KEY + ":" + com.devmanchego.javaguard.change.Hashing.shortSha256(active, 12);
    }

    private boolean active(AstCheck check) {
        java.util.Set<com.devmanchego.javaguard.project.Framework> required = check.requiresAnyOf();
        return required.isEmpty() || required.stream().anyMatch(frameworks::contains);
    }

    @Override
    public List<Finding> analyze(String relativePath, String content) {
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setAttributeComments(false));
        ParseResult<CompilationUnit> result;
        try {
            result = parser.parse(content);
        } catch (ParseProblemException e) {
            return List.of(SynCheck.parseFailure(relativePath, firstMessage(e.getProblems()), 0, 0));
        }
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            Problem p = result.getProblem(0);
            int line = p.getLocation().flatMap(l -> l.getBegin().getRange()).map(r -> r.begin.line).orElse(0);
            int col = p.getLocation().flatMap(l -> l.getBegin().getRange()).map(r -> r.begin.column).orElse(0);
            return List.of(SynCheck.parseFailure(relativePath, p.getVerboseMessage(), line, col));
        }

        List<com.github.javaparser.ast.comments.Comment> comments = result.getCommentsCollection()
                .map(cc -> List.<com.github.javaparser.ast.comments.Comment>copyOf(cc.getComments()))
                .orElse(List.of());
        AstCheckContext ctx = new AstCheckContext(relativePath, result.getResult().get(), content.lines().toList(),
                testScope.test(relativePath), comments, frameworks, entities);
        List<Finding> findings = new ArrayList<>();
        for (AstCheck check : checks) {
            if (!active(check) || ctx.testScope() && check.mainOnly()) {
                continue; // Catalog scope "main": test sources are not judged by this rule.
            }
            try {
                findings.addAll(check.check(ctx));
            } catch (RuntimeException e) {
                log.warning(() -> "Check " + check.info().ruleId() + " failed on " + relativePath + ": " + e);
            }
        }
        return findings;
    }

    private static String firstMessage(List<Problem> problems) {
        return problems.isEmpty() ? "parse error" : problems.get(0).getVerboseMessage();
    }

    private static boolean looksLikeTestSource(String relativePath) {
        String p = relativePath.replace('\\', '/');
        return p.contains("/src/test/") || p.startsWith("src/test/") || p.endsWith("Test.java")
                || p.endsWith("Tests.java") || p.endsWith("IT.java");
    }
}
