package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.analysis.AstCheck;
import com.devmanchego.javaguard.analysis.CheckRegistry;
import com.devmanchego.javaguard.analysis.PmdEngine;
import com.devmanchego.javaguard.analysis.RuleInfo;
import com.devmanchego.javaguard.analysis.syntax.SynCheck;
import com.devmanchego.javaguard.cli.ExitCode;
import com.devmanchego.javaguard.cli.Main;
import com.devmanchego.javaguard.config.ConfigException;
import com.devmanchego.javaguard.analysis.ArchitectureCheck;
import com.devmanchego.javaguard.gate.ApiCompatibilityAnalyzer;
import com.devmanchego.javaguard.integrity.TestIntegrityEngine;
import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Severity;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Lists rules with id, category, severity, engine, gate, enabled state
 * (specification &sect;4, .private/spec/02-RULES-CATALOG.md). Grows as later
 * phases add engines (Error Prone, SpotBugs, Spring/JPA, test integrity,
 * dependencies, API compatibility, architecture).
 */
@Command(mixinStandardHelpOptions = true, name = "rules", description = "List JavaGuard rules and their configuration.")
public final class RulesCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Option(names = "--category", description = "Filter by category, e.g. SECURITY, PERFORMANCE, RELIABILITY.")
    private String category;

    @Option(names = "--enabled", description = "Show only enabled rules.")
    private boolean enabledOnly;

    @Override
    public Integer call() {
        Category categoryFilter;
        try {
            categoryFilter = category == null ? null : Category.valueOf(category.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            System.err.println("javaguard rules: unknown category '" + category + "'");
            return ExitCode.USAGE_ERROR.code();
        }

        Path root = ProjectRoots.resolve(main);
        JavaGuardConfig config = loadConfig(root);

        List<Row> rows = allRuleInfos()
                .map(info -> toRow(info, config))
                .filter(r -> categoryFilter == null || r.info.category() == categoryFilter)
                .filter(r -> !enabledOnly || r.enabled)
                .sorted(Comparator.comparing(r -> r.info.ruleId()))
                .toList();

        printTable(rows);
        return ExitCode.SUCCESS.code();
    }

    private static Stream<RuleInfo> allRuleInfos() {
        return Stream.of(Stream.of(SynCheck.INFO), CheckRegistry.ALL.stream().map(AstCheck::info),
                PmdEngine.RULES.stream(), TestIntegrityEngine.RULES.stream(),
                Stream.of(ArchitectureCheck.INFO, ApiCompatibilityAnalyzer.INFO),
                com.devmanchego.javaguard.analysis.db.MigrationChecks.RULES.stream(),
                com.devmanchego.javaguard.analysis.http.HttpContractAnalyzer.RULES.stream(),
                Stream.of(com.devmanchego.javaguard.analysis.config.ConfigSecretCheck.INFO,
                        com.devmanchego.javaguard.analysis.config.ConfigKeyAnalyzer.INFO)).flatMap(java.util.function.Function.identity());
    }

    private record Row(RuleInfo info, Severity effectiveSeverity, boolean enabled) {
    }

    private static Row toRow(RuleInfo info, JavaGuardConfig config) {
        Optional<JavaGuardConfig.RuleOverride> override = config.rule(info.ruleId());
        Severity severity = override.flatMap(JavaGuardConfig.RuleOverride::severity).orElse(info.defaultSeverity());
        boolean engineEnabled = !info.engine().equals("pmd") || config.pmd().enabled();
        boolean ruleEnabled = override.flatMap(JavaGuardConfig.RuleOverride::enabled).orElse(true);
        return new Row(info, severity, engineEnabled && ruleEnabled);
    }

    private static void printTable(List<Row> rows) {
        String header = String.format("%-16s %-14s %-9s %-8s %-4s %-8s %s",
                "ID", "CATEGORY", "SEVERITY", "ENGINE", "GATE", "ENABLED", "TITLE");
        List<String> lines = new ArrayList<>();
        lines.add(header);
        for (Row r : rows) {
            lines.add(String.format("%-16s %-14s %-9s %-8s %-4s %-8s %s",
                    r.info.ruleId(), r.info.category(), r.effectiveSeverity(), r.info.engine(), r.info.gate(),
                    r.enabled ? "yes" : "no", r.info.title()));
        }
        lines.add(rows.size() + " rule(s)");
        System.out.println(String.join(System.lineSeparator(), lines));
    }

    /**
     * Configuration errors never block a manual listing: fall back to
     * defaults and still show every rule's default severity/enabled state.
     */
    private JavaGuardConfig loadConfig(Path root) {
        Optional<Path> explicitConfig = Optional.ofNullable(main == null ? null : main.configFile);
        ConfigSources sources = ConfigSources.standard(root, explicitConfig,
                Path.of(System.getProperty("user.home")), System.getenv());
        try {
            return ConfigLoader.load(sources).config();
        } catch (ConfigException e) {
            System.err.println("javaguard rules: " + e.getMessage() + "; using built-in defaults");
            try {
                return ConfigLoader.load(ConfigSources.defaultsOnly()).config();
            } catch (ConfigException impossible) {
                throw new IllegalStateException("Built-in defaults are invalid", impossible);
            }
        }
    }
}
