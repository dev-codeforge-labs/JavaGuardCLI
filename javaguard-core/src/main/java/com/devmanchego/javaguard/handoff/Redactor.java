package com.devmanchego.javaguard.handoff;

import com.devmanchego.javaguard.analysis.secrets.SecretPatterns;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What a handoff must not leak (specification &sect;14.3). Applied to the whole document - source, diffs, findings and
 * transcript excerpts alike - just before it is written:
 * <ul>
 *   <li>credentials in a known format (the JG-SEC-008 patterns, shared via {@link SecretPatterns}) become
 *       {@code <redacted:kind>};</li>
 *   <li>the value of a secret-named assignment ({@code password = "..."}, {@code "apiKey": "..."},
 *       {@code token: ...}) becomes {@code <redacted:secret>}, unless it is a placeholder;</li>
 *   <li>{@code Authorization: Bearer ...} tokens;</li>
 *   <li>the user's {@code handoff.redactPatterns} (regular expressions) become {@code <redacted:custom>};</li>
 *   <li>absolute file paths: inside the project they are made project-relative, anywhere else they become
 *       {@code <external>}.</li>
 * </ul>
 */
public final class Redactor {

    private static final Logger LOG = Logger.getLogger(Redactor.class.getName());
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)([\\w.\\-]*(?:password|passwd|pwd|secret|api[_-]?key|access[_-]?key|token|private[_-]?key|credentials?)[\\w.\\-]*"
                    + "[\"']?\\s*[:=]\\s*)([\"']?)([^\\s\"',;)}\\]]{8,})(\\2)");
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]{16,}");
    private static final Pattern WINDOWS_PATH = Pattern.compile("(?<![\\w])[A-Za-z]:[\\\\/][^\\s\"'`<>|*?)\\]]+");
    private static final Pattern POSIX_PATH = Pattern.compile("(?<![\\w:/.])/(?:home|Users|tmp|var|opt|usr|etc|mnt|root|srv)/[^\\s\"'`<>|*?)\\]]+");

    private final Path projectRoot;
    private final List<Pattern> custom = new ArrayList<>();

    public Redactor(Path projectRoot, List<String> customPatterns) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        for (String pattern : customPatterns) {
            try {
                custom.add(Pattern.compile(pattern));
            } catch (PatternSyntaxException e) {
                LOG.warning("Ignoring invalid handoff.redactPatterns entry '" + pattern + "': " + e.getDescription());
            }
        }
    }

    public String redact(String text) {
        String out = redactKnownFormats(text);
        out = redactAssignments(out);
        out = BEARER.matcher(out).replaceAll("$1<redacted:token>");
        for (Pattern pattern : custom) {
            out = pattern.matcher(out).replaceAll("<redacted:custom>");
        }
        return redactPaths(out);
    }

    private static String redactKnownFormats(String text) {
        List<SecretPatterns.Match> matches = SecretPatterns.findKnownFormats(text);
        if (matches.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        for (SecretPatterns.Match m : matches) {
            if (m.start() < cursor) {
                continue;
            }
            sb.append(text, cursor, m.start()).append("<redacted:").append(m.kind()).append('>');
            cursor = m.end();
        }
        return sb.append(text.substring(cursor)).toString();
    }

    private static String redactAssignments(String text) {
        Matcher m = ASSIGNMENT.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = m.group(3);
            boolean redact = !SecretPatterns.isPlaceholder(value) && !value.startsWith("<redacted");
            m.appendReplacement(sb, Matcher.quoteReplacement(redact ? m.group(1) + m.group(2) + "<redacted:secret>" + m.group(4) : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String redactPaths(String text) {
        String root = projectRoot.toString().replace('\\', '/');
        text = replacePaths(WINDOWS_PATH, text, root);
        return replacePaths(POSIX_PATH, text, root);
    }

    private static String replacePaths(Pattern pattern, String text, String root) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String normalized = m.group().replace('\\', '/');
            String replacement;
            if (normalized.equalsIgnoreCase(root)) {
                replacement = ".";
            } else if (normalized.regionMatches(true, 0, root + "/", 0, root.length() + 1)) {
                replacement = normalized.substring(root.length() + 1);
            } else {
                replacement = "<external>";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
