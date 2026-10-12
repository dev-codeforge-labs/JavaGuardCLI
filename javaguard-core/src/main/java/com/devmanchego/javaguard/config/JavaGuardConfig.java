package com.devmanchego.javaguard.config;

import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.model.Severity;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Effective JavaGuardCLI configuration (specification &sect;18). Immutable;
 * produced only by {@link ConfigLoader}, which guarantees every field is
 * non-null and validated. Defaults live in {@code javaguard-defaults.yml}.
 */
public record JavaGuardConfig(
        List<String> exclude,
        Build build,
        FastGate fastGate,
        FullGate fullGate,
        Tests tests,
        Toggle errorprone,
        Toggle pmd,
        Toggle spotbugs,
        Map<String, RuleOverride> rules,
        Suppressions suppressions,
        Deps deps,
        Api api,
        Arch arch,
        Report report,
        Loop loop,
        Handoff handoff,
        Consult consult,
        // YAML key "notify" (spec section 18.2); a record component cannot be named notify.
        @JsonProperty("notify") Notify notifications,
        State state
) {

    public JavaGuardConfig {
        exclude = List.copyOf(exclude);
        rules = Map.copyOf(rules);
    }

    /** Override for one rule, if configured. */
    public Optional<RuleOverride> rule(String ruleId) {
        return Optional.ofNullable(rules.get(ruleId));
    }

    public enum BuildTool { AUTO, MAVEN, GRADLE }

    /** Whether a class of findings is advisory or blocking. */
    public enum Policy { ADVISE, BLOCK }

    public enum TestScope { AFFECTED, MODULE, ALL, NONE }

    public record Build(BuildTool tool, Maven maven, Gradle gradle) {
    }

    public record Maven(boolean useDaemon, boolean offlineFirst, List<String> extraArgs) {
        public Maven {
            extraArgs = List.copyOf(extraArgs);
        }
    }

    public record Gradle(List<String> extraArgs) {
        public Gradle {
            extraArgs = List.copyOf(extraArgs);
        }
    }

    public record FastGate(boolean enabled, int budgetMillis, Policy compileErrors, boolean inProcessCompile) {
    }

    public record FullGate(boolean enabled, int budgetSeconds, int maxConsecutiveBlocks) {
    }

    public record Tests(TestScope scope, int maxClasses, boolean includeIntegration, Policy unrelatedFailures) {
    }

    public record Toggle(boolean enabled) {
    }

    /**
     * Per-rule override (specification &sect;18.2 {@code rules}). Absent
     * values mean "use the rule's default".
     *
     * @param enabled  override of the rule's enabled state
     * @param severity override of the rule's default severity
     * @param exclude  path globs where the rule does not apply
     * @param options  rule-specific options (e.g. {@code allow} for JG-AI-001), raw
     */
    public record RuleOverride(Optional<Boolean> enabled, Optional<Severity> severity, List<String> exclude,
                               Map<String, Object> options) {
        public RuleOverride {
            exclude = List.copyOf(exclude);
            options = Map.copyOf(options);
        }
    }

    public record Suppressions(boolean allowAgentAdded) {
    }

    public record Deps(Osv osv) {
    }

    public record Osv(boolean enabled, int timeoutSeconds) {
    }

    public record Api(List<String> publicPackages) {
        public Api {
            publicPackages = List.copyOf(publicPackages);
        }
    }

    public record Arch(List<ArchRule> rules) {
        public Arch {
            rules = List.copyOf(rules);
        }
    }

    public record ArchRule(String from, List<String> mustNotDependOn) {
        public ArchRule {
            mustNotDependOn = mustNotDependOn == null ? List.of() : List.copyOf(mustNotDependOn);
        }
    }

    public record Report(int maxFindings, int maxChars, boolean ascii, boolean adviseOnPostEdit, boolean sarif) {
    }

    public record Loop(int repeatThreshold, int oscillationWindow, int noProgressWindow, int maxBlockedRuns,
                       int maxSnapshots) {
    }

    public record Handoff(boolean copyToProjectRoot, int maxFileLines, int maxDiffLines, int transcriptMessages,
                          List<String> redactPatterns) {
        public Handoff {
            redactPatterns = List.copyOf(redactPatterns);
        }
    }

    public record Consult(boolean enabled, List<AgentKind> order, int timeoutSeconds, ClaudeConsult claude,
                          Map<String, String> commands) {
        public Consult {
            order = List.copyOf(order);
            commands = Map.copyOf(commands);
        }
    }

    public record ClaudeConsult(String model) {
    }

    public record Notify(boolean terminalBell, boolean sound, boolean toast, boolean consultation, Ntfy ntfy) {
    }

    public record Ntfy(boolean enabled, String url) {
    }

    public record State(int retentionDays) {
    }
}
