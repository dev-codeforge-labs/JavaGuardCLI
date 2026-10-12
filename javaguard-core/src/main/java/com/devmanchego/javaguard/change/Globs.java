package com.devmanchego.javaguard.change;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Glob matching on {@code '/'}-separated, project-relative paths, with the
 * same semantics on every operating system ({@code java.nio}'s
 * {@code PathMatcher} follows platform separator rules, which makes the same
 * {@code javaguard.yml} behave differently on Windows).
 *
 * <ul>
 *   <li>{@code **}{@code /} matches zero or more directories;</li>
 *   <li>{@code **} matches anything, including {@code /};</li>
 *   <li>{@code *} matches within one path segment;</li>
 *   <li>{@code ?} matches one character other than {@code /};</li>
 *   <li>everything else is literal.</li>
 * </ul>
 */
public final class Globs {

    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    private Globs() {
    }

    public static boolean matches(String glob, String path) {
        String normalized = path.replace('\\', '/');
        return CACHE.computeIfAbsent(glob, Globs::compile).matcher(normalized).matches();
    }

    static Pattern compile(String glob) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                    regex.append("(?:.*/)?");
                    i += 3;
                } else {
                    regex.append(".*");
                    i += 2;
                }
            } else if (c == '*') {
                regex.append("[^/]*");
                i++;
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return Pattern.compile(regex.toString());
    }
}
