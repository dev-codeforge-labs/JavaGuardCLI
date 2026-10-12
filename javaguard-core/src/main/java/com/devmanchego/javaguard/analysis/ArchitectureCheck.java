package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.ImportDeclaration;

import java.util.ArrayList;
import java.util.List;

/**
 * JG-ARCH-001 (specification &sect;11.6): the configured architecture rules, evaluated on the imports of a
 * changed file. Each rule is {@code from: <package glob>, mustNotDependOn: [<package glob>, ...]}; a file whose
 * package matches {@code from} that imports a package matching any {@code mustNotDependOn} gets a finding on the
 * import. There are no rules by default. Built per configuration, so unlike the registry's checks it is passed to
 * the engine as an extra check.
 */
public final class ArchitectureCheck extends AbstractAstCheck {

    public static final RuleInfo INFO = RuleInfo.ast("JG-ARCH-001", "Forbidden dependency", Category.ARCHITECTURE, Severity.MAJOR);

    private final List<JavaGuardConfig.ArchRule> rules;

    public ArchitectureCheck(List<JavaGuardConfig.ArchRule> rules) {
        this.rules = List.copyOf(rules);
    }

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public boolean mainOnly() {
        return false; // Catalog scope: spec section 11.6, every source.
    }

    /** Stable text of the configured rules, for the baseline-findings cache key. */
    public String signature() {
        StringBuilder sb = new StringBuilder();
        rules.forEach(r -> sb.append(r.from()).append("->").append(r.mustNotDependOn()).append(';'));
        return sb.toString();
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        String filePackage = ctx.unit().getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        for (JavaGuardConfig.ArchRule rule : rules) {
            if (!PackageGlobs.matches(rule.from(), filePackage)) {
                continue;
            }
            for (ImportDeclaration imp : ctx.unit().getImports()) {
                String imported = PackageGlobs.packageOfImport(imp.getNameAsString());
                for (String forbidden : rule.mustNotDependOn()) {
                    if (PackageGlobs.matches(forbidden, imported)) {
                        out.add(finding(ctx, imp, "Package " + (filePackage.isEmpty() ? "(default)" : filePackage)
                                + " must not depend on " + forbidden + ", but imports " + imp.getNameAsString() + ".",
                                "Depend on an abstraction in an allowed package instead, or move the code (architecture "
                                        + "rule from: " + rule.from() + ")."));
                        break;
                    }
                }
            }
        }
        return out;
    }
}
