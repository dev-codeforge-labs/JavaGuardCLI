package com.devmanchego.javaguard.analysis.config;

import com.devmanchego.javaguard.analysis.RuleInfo;
import com.devmanchego.javaguard.analysis.secrets.SecretPatterns;
import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.change.ScopedAnalysis;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * JG-CFG-001 (catalog &sect;1.13): a literal secret in a Spring Boot configuration file. JG-SEC-008's patterns applied to
 * each key: a secret-shaped key name holding a literal (not a {@code ${...}} reference, not empty, not a placeholder), or
 * a value in a known credential format whatever its key. Keys that only mention a secret ({@code token-uri},
 * {@code api-key-header}, {@code jwt.token-validity}) and values that are URLs, numbers, durations or paths are not
 * secrets. Like JG-SEC-008 it reports every match in the file; {@link ScopedAnalysis} keeps the ones the session added.
 * The value itself is never repeated in the finding. Test resources and development profiles ({@code dev}, {@code local},
 * {@code test}...) report MAJOR instead of BLOCKER.
 */
public final class ConfigSecretCheck implements ScopedAnalysis.FileAnalyzer {

    public static final RuleInfo INFO = new RuleInfo("JG-CFG-001", "Secret in configuration", Category.SECRET, Severity.BLOCKER,
            "config", "F");
    /** Changes whenever this check's behavior changes; part of the baseline cache key. */
    public static final String KEY = "cfg-v1";

    private static final Set<String> NOT_SECRET_ENDINGS = Set.of("uri", "url", "endpoint", "header", "headername", "name", "type",
            "validity", "validityseconds", "validityinseconds", "expiration", "expiry", "ttl", "timeout", "enabled", "path",
            "location", "file", "algorithm", "length", "size", "prefix", "issuer", "storetype", "mode", "format", "parameter",
            "param", "cookie", "attribute", "strategy", "provider", "policy", "store");
    private static final Set<String> DEVELOPMENT_PROFILES = Set.of("dev", "development", "local", "test", "tests", "h2", "docker",
            "ci", "it", "e2e", "demo", "sample", "samples", "example", "examples");
    private static final Pattern NOT_A_SECRET_VALUE = Pattern.compile(
            "(?i)^(?:[a-z][a-z0-9+.-]*://.*|classpath:.*|file:.*|[./~].*|-?\\d+(?:\\.\\d+)?[a-z]{0,2}|true|false|on|off|yes|no|none|null)$");

    @Override
    public List<Finding> analyze(String relativePath, String content) {
        if (!ConfigFiles.isConfigFile(relativePath)) {
            return List.of();
        }
        List<Finding> out = new ArrayList<>();
        for (ConfigFiles.Entry entry : ConfigFiles.parse(relativePath, content)) {
            String value = entry.value();
            if (value.isEmpty() || value.contains("${")) {
                continue;
            }
            List<SecretPatterns.Match> formats = SecretPatterns.findKnownFormats(value);
            if (!formats.isEmpty()) {
                out.add(finding(relativePath, entry, "'" + entry.key() + "' holds a value in the format of a " + formats.get(0).kind() + "."));
            } else if (secretKey(entry.leaf()) && SecretPatterns.isSecretShaped(entry.leaf(), value)
                    && !NOT_A_SECRET_VALUE.matcher(value).matches() && !value.contains(" ")) {
                out.add(finding(relativePath, entry, "'" + entry.key() + "' holds a literal value that looks like a secret."));
            }
        }
        return out;
    }

    private static boolean secretKey(String leaf) {
        String normalized = leaf.toLowerCase(Locale.ROOT).replaceAll("[-_]", "");
        return SecretPatterns.looksLikeSecretName(leaf) && NOT_SECRET_ENDINGS.stream().noneMatch(normalized::endsWith);
    }

    private static Finding finding(String rel, ConfigFiles.Entry entry, String message) {
        boolean development = ConfigFiles.isTestResource(rel) || DEVELOPMENT_PROFILES.stream()
                .anyMatch(p -> entry.profile().toLowerCase(Locale.ROOT).matches("(?:.*[-,]\\s*)?" + p + "(?:\\s*[-,].*)?"));
        Severity severity = development ? Severity.MAJOR : Severity.BLOCKER;
        return new Finding(INFO.ruleId(), INFO.title(), INFO.category(), severity, INFO.engine(), Optional.of(Path.of(rel)),
                entry.line(), 0, entry.line(), message + " Configuration files are committed and packaged into the artifact.",
                "Replace the value with a reference such as ${" + envName(entry.key()) + "} and supply it from the environment or a "
                        + "secret manager; rotate the secret if it was ever committed.",
                Optional.empty(), Map.of("key", entry.key()), Fingerprints.of(INFO.ruleId(), rel.replace('\\', '/'), entry.key()));
    }

    private static String envName(String key) {
        return key.replaceAll("\\[\\d+]", "").replaceAll("[.-]", "_").toUpperCase(Locale.ROOT);
    }
}
