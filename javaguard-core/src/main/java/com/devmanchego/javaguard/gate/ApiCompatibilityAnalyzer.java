package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.analysis.PackageGlobs;
import com.devmanchego.javaguard.analysis.RuleInfo;
import com.devmanchego.javaguard.analysis.api.ApiSignatures;
import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.ReactorModules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JG-API-001 (specification &sect;11.5): the public or protected API of a changed main source lost or changed a
 * member. Applies to classes of modules that other reactor modules depend on, and to packages listed in
 * {@code api.publicPackages}; a leaf module with no listed packages has no consumer to protect. The finding names
 * the dependent modules. Dependents are rebuilt by the end-of-turn compile, so a real break there is already a
 * blocking compile error; this rule exists for consumers the build cannot see (other repositories, released
 * artifacts), which is why it is advisory.
 */
public final class ApiCompatibilityAnalyzer implements DeepAnalyzer {

    public static final RuleInfo INFO = new RuleInfo("JG-API-001", "Public API changed", Category.API_COMPATIBILITY,
            Severity.MAJOR, "diff", "E");

    private static final Logger LOG = Logger.getLogger(ApiCompatibilityAnalyzer.class.getName());
    private static final int MAX_FINDINGS_PER_FILE = 10;

    @Override
    public List<Finding> analyze(Context context) {
        List<Finding> findings = new ArrayList<>();
        List<String> publicPackages = context.config().api().publicPackages();
        for (Path file : context.changedFiles()) {
            if (!file.toString().endsWith(".java")) {
                continue;
            }
            Path absolute = file.toAbsolutePath().normalize();
            Optional<ReactorModules.Module> module = context.reactor().moduleOf(absolute);
            if (module.isEmpty() || !module.get().info().isMainSource(absolute)) {
                continue;
            }
            Optional<String> baseline = context.baselineOf().apply(absolute);
            if (baseline.isEmpty()) {
                continue; // A new file removes nothing.
            }
            List<String> dependents = dependentsOf(context.reactor(), module.get());
            try {
                String current = Files.isRegularFile(absolute) ? Files.readString(absolute, StandardCharsets.UTF_8) : "";
                ApiSignatures.Api before = ApiSignatures.extract(baseline.get());
                ApiSignatures.Api after = ApiSignatures.extract(current);
                boolean listed = publicPackages.stream().anyMatch(glob -> PackageGlobs.matches(glob, before.packageName()));
                if (dependents.isEmpty() && !listed) {
                    continue;
                }
                String rel = context.paths().projectRoot().relativize(absolute).toString().replace('\\', '/');
                findings.addAll(describe(rel, ApiSignatures.compare(before, after), dependents));
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "API comparison failed for " + absolute, e);
            }
        }
        return findings;
    }

    private static List<Finding> describe(String rel, List<ApiSignatures.Difference> differences, List<String> dependents) {
        List<Finding> out = new ArrayList<>();
        String audience = dependents.isEmpty()
                ? "No module of this build depends on it; external consumers may."
                : "Dependent modules that may break: " + String.join(", ", dependents) + ".";
        for (ApiSignatures.Difference difference : differences) {
            if (out.size() == MAX_FINDINGS_PER_FILE) {
                String message = "(+" + (differences.size() - MAX_FINDINGS_PER_FILE) + " more public API changes in this file.)";
                out.add(finding(rel, 1, message, "Apply the same fix to every changed public member: keep each old signature next to the new "
                        + "one (deprecated, delegating to it), or update every consumer in the same change."));
                break;
            }
            ApiSignatures.Signature before = difference.before();
            String message = difference.kind() == ApiSignatures.Difference.Kind.REMOVED
                    ? "Public API removed: " + before.display() + ". " + audience
                    : "Public API changed: " + before.display() + " was '" + before.full() + "', now '"
                            + difference.after().orElseThrow().full() + "'. " + audience;
            int line = difference.after().map(ApiSignatures.Signature::line).orElse(1);
            out.add(finding(rel, line, message, "Keep the old signature (deprecate it) and add the new one next to it, or "
                    + "make sure every consumer is updated in the same change."));
        }
        return out;
    }

    private static Finding finding(String rel, int line, String message, String remediation) {
        return new Finding(INFO.ruleId(), INFO.title(), INFO.category(), INFO.defaultSeverity(), INFO.engine(),
                Optional.of(Path.of(rel)), line, 0, line, message, remediation, Optional.empty(), Map.of(),
                Fingerprints.of(INFO.ruleId(), rel, message));
    }

    /** Artifact ids (else directory names) of the modules that depend on {@code module}, directly or transitively. */
    private static List<String> dependentsOf(ReactorModules reactor, ReactorModules.Module module) {
        Set<Path> roots = reactor.withDependents(Set.of(module.info().moduleRoot()));
        roots.remove(module.info().moduleRoot());
        Set<String> names = new LinkedHashSet<>();
        for (Path root : roots) {
            reactor.all().stream().filter(m -> m.info().moduleRoot().equals(root)).findFirst()
                    .ifPresent(m -> names.add(m.artifactId().orElse(root.getFileName().toString())));
        }
        return new ArrayList<>(names);
    }
}
