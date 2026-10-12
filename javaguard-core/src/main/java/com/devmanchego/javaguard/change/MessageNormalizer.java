package com.devmanchego.javaguard.change;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Normalizes finding messages so their identity survives edits that do not
 * change the problem itself (specification &sect;13.1): line/column numbers,
 * absolute paths, hex addresses, timestamps, durations and synthetic class
 * names become placeholders; whitespace collapses. Identifiers and literals
 * are kept, because they are what distinguishes one error from another.
 *
 * <p>The rules run in the order listed, and the order matters (a path must be
 * replaced before its {@code :line} suffix is, a lambda class's
 * {@code /0x...} suffix before generic hex addresses are). The authoritative
 * behavior is the table in {@code MessageNormalizerTest}; any change there is
 * a breaking change to stored fingerprints ({@link Fingerprints#VERSION}).
 */
public final class MessageNormalizer {

    private record Rule(Pattern pattern, String replacement) {
        static Rule of(String regex, String replacement) {
            return new Rule(Pattern.compile(regex), replacement);
        }

        String apply(String text) {
            return pattern.matcher(text).replaceAll(replacement);
        }
    }

    private static final String PATH_CHARS = "[\\w.$@+-]";

    private static final List<Rule> RULES = List.of(
            // 1. Absolute paths: file URIs, Windows drive paths, POSIX paths with 2+ segments.
            Rule.of("file:/+[^\\s'\"()<>]*", "<path>"),
            Rule.of("(?<![\\w])[A-Za-z]:[\\\\/][^\\s:'\"()<>|?*]*", "<path>"),
            Rule.of("(?<![\\w.:/<>])/(?:" + PATH_CHARS + "+/)+" + PATH_CHARS + "*", "<path>"),
            Rule.of("<path>:\\d+(?::\\d+)?", "<path>:<n>"),
            // 2. Timestamps and times (before durations and line suffixes, which share digits and colons).
            Rule.of("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})?", "<timestamp>"),
            Rule.of("\\b\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\b", "<time>"),
            // 3. Line and column locations.
            Rule.of("\\.java:\\d+(?::\\d+)?", ".java:<n>"),
            Rule.of(":\\[\\d+,\\d+\\]", ":[<n>,<n>]"),
            Rule.of("(?i)\\bline\\s+\\d+", "line <n>"),
            Rule.of("(?i)\\bcolumn\\s+\\d+", "column <n>"),
            Rule.of("(?i)\\bcol\\s+\\d+", "col <n>"),
            // 4. Synthetic and generated class names (before hex: they embed /0x... addresses).
            // At least one suffix is required: with both optional, the already-normalized
            // "$$Lambda<n>" would match again and grow a second "<n>" (not idempotent).
            Rule.of("\\$\\$Lambda(?:\\$\\d+(?:/0x[0-9a-fA-F]+)?|/0x[0-9a-fA-F]+)", "\\$\\$Lambda<n>"),
            Rule.of("\\$\\$(?:EnhancerBySpringCGLIB|FastClassBySpringCGLIB|SpringCGLIB|HibernateProxy|ByteBuddy|MockitoMock)"
                    + "\\$\\$[0-9A-Za-z]+", "\\$\\$<proxy>"),
            Rule.of("lambda\\$([A-Za-z_]\\w*)\\$\\d+", "lambda\\$$1\\$<n>"),
            Rule.of("\\$\\d+\\b", "\\$<n>"),
            // 5. Hex addresses (identity hash codes, native pointers); short hex literals are kept.
            Rule.of("@[0-9a-fA-F]{4,}\\b", "@<hex>"),
            Rule.of("\\b0x[0-9a-fA-F]{8,}\\b", "0x<hex>"),
            // 6. Durations.
            Rule.of("\\bPT[0-9HMS.]+", "<duration>"),
            Rule.of("\\b\\d+(?:\\.\\d+)?\\s?(?:ms|millis|milliseconds|s|sec|secs|seconds|min|mins|minutes)\\b", "<duration>"),
            // 7. Whitespace.
            Rule.of("\\s+", " "));

    private MessageNormalizer() {
    }

    public static String normalize(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        String text = message;
        for (Rule rule : RULES) {
            text = rule.apply(text);
        }
        return text.strip();
    }
}
