package com.devmanchego.javaguard.change;

import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Configuration-driven suppression and severity overrides (specification
 * &sect;11.3 "config suppression", &sect;18.2 {@code exclude} and
 * {@code rules}):
 * <ul>
 *   <li>files matching a global {@code exclude} glob produce no file findings;</li>
 *   <li>{@code rules.<id>.enabled: false} drops the rule's findings;</li>
 *   <li>{@code rules.<id>.exclude} drops them for matching files;</li>
 *   <li>{@code rules.<id>.severity} replaces the finding's severity.</li>
 * </ul>
 * Findings without a file are affected only by {@code enabled} and {@code severity}.
 */
public final class ConfigFindingPolicy {

    private final List<String> exclude;
    private final Map<String, JavaGuardConfig.RuleOverride> rules;

    public ConfigFindingPolicy(List<String> exclude, Map<String, JavaGuardConfig.RuleOverride> rules) {
        this.exclude = List.copyOf(exclude);
        this.rules = Map.copyOf(rules);
    }

    public static ConfigFindingPolicy of(JavaGuardConfig config) {
        return new ConfigFindingPolicy(config.exclude(), config.rules());
    }

    public static ConfigFindingPolicy none() {
        return new ConfigFindingPolicy(List.of(), Map.of());
    }

    /** True when a whole file is outside analysis (global {@code exclude}). */
    public boolean isExcluded(String relativePath) {
        return exclude.stream().anyMatch(g -> Globs.matches(g, relativePath));
    }

    public List<Finding> apply(List<Finding> findings) {
        List<Finding> kept = new ArrayList<>();
        for (Finding f : findings) {
            Optional<String> path = f.file().map(p -> p.toString().replace('\\', '/'));
            if (path.isPresent() && isExcluded(path.get())) {
                continue;
            }
            JavaGuardConfig.RuleOverride override = rules.get(f.ruleId());
            if (override == null) {
                kept.add(f);
                continue;
            }
            if (override.enabled().filter(enabled -> !enabled).isPresent()) {
                continue;
            }
            if (path.isPresent() && override.exclude().stream().anyMatch(g -> Globs.matches(g, path.get()))) {
                continue;
            }
            kept.add(override.severity().map(s -> withSeverity(f, s)).orElse(f));
        }
        return kept;
    }

    /**
     * Stable key describing this policy, for cache keys: findings computed
     * under a different policy must not be reused.
     */
    public String key() {
        StringBuilder sb = new StringBuilder("exclude=").append(exclude).append(";rules=");
        new TreeMap<>(rules).forEach((id, o) -> sb.append(id).append('{')
                .append(o.enabled().map(String::valueOf).orElse("-")).append(',')
                .append(o.severity().map(Enum::name).orElse("-")).append(',')
                .append(o.exclude()).append('}'));
        return Hashing.shortSha256(sb.toString(), 16);
    }

    private static Finding withSeverity(Finding f, Severity severity) {
        return new Finding(f.ruleId(), f.title(), f.category(), severity, f.engine(), f.file(), f.line(), f.column(),
                f.endLine(), f.message(), f.remediation(), f.snippet(), f.detail(), f.fingerprint());
    }
}
