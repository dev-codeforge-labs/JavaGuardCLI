package com.devmanchego.javaguard.analysis.secrets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The secret-detection heuristics behind JG-SEC-008 (catalog &sect;1.2),
 * factored out so they can be reused verbatim by handoff redaction
 * (specification &sect;14.3: "the secrets detector (catalog JG-SEC-008
 * patterns + {@code handoff.redactPatterns})"). Nothing here depends on
 * JavaParser or an AST: {@link #findKnownFormats(String)} scans any text -
 * a Java string literal's value, a log line, a transcript excerpt - for
 * substrings shaped like a real credential, independent of where they came
 * from.
 */
public final class SecretPatterns {

    /** One match of a known credential format inside a larger text. */
    public record Match(String kind, int start, int end) {
    }

    private static final Pattern SECRET_NAME = Pattern.compile(
            "(?i).*(password|passwd|pwd|secret|api[_-]?key|access[_-]?key|token|private[_-]?key|credentials).*");
    private static final int MIN_SECRET_VALUE_LENGTH = 8;

    private static final Map<String, Pattern> KNOWN_FORMATS = Map.of(
            "AWS access key", Pattern.compile("AKIA[0-9A-Z]{16}"),
            "GitHub token", Pattern.compile("gh[pousr]_[A-Za-z0-9]{36,}"),
            "Slack token", Pattern.compile("xox[baprs]-[A-Za-z0-9-]+"),
            "Google API key", Pattern.compile("AIza[0-9A-Za-z_-]{35}"),
            "private key", Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            "JWT", Pattern.compile("eyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+"),
            "JDBC URL with an embedded password", Pattern.compile("jdbc:[a-zA-Z0-9:/.@_-]*password="));

    private SecretPatterns() {
    }

    /** Every known-credential-format match in {@code text}, in order of appearance. */
    public static List<Match> findKnownFormats(String text) {
        List<Match> matches = new ArrayList<>();
        KNOWN_FORMATS.forEach((kind, pattern) -> {
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                matches.add(new Match(kind, m.start(), m.end()));
            }
        });
        matches.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return matches;
    }

    /** True when {@code identifierName} (a field/variable/parameter name) reads as secret-shaped. */
    public static boolean looksLikeSecretName(String identifierName) {
        return SECRET_NAME.matcher(identifierName).matches();
    }

    /** {@link #looksLikeSecretName(String)} holding a non-trivial, non-placeholder value. */
    public static boolean isSecretShaped(String identifierName, String value) {
        return looksLikeSecretName(identifierName) && value.length() >= MIN_SECRET_VALUE_LENGTH && !isPlaceholder(value);
    }

    /** A value that only looks like a secret because of its name - a blank, a marker, or unresolved config syntax. */
    public static boolean isPlaceholder(String rawValue) {
        String v = rawValue.strip();
        if (v.isEmpty()) {
            return true;
        }
        if (v.matches("(?i)change(_?me)?|todo|n/?a|placeholder")) {
            return true;
        }
        if (v.chars().allMatch(c -> c == '*')) {
            return true;
        }
        return (v.startsWith("${") && v.endsWith("}")) || (v.startsWith("<") && v.endsWith(">"));
    }
}
