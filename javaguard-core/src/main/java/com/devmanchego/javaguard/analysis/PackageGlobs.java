package com.devmanchego.javaguard.analysis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Package-name patterns for the architecture and API rules (specification &sect;11.5, &sect;11.6).
 * <ul>
 *   <li>{@code com.acme.domain..} or {@code com.acme.domain.**}: the package and every sub-package;</li>
 *   <li>{@code *} inside a pattern: one segment, {@code **}: any number of segments;</li>
 *   <li>anything else: the exact package.</li>
 * </ul>
 */
public final class PackageGlobs {

    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    private PackageGlobs() {
    }

    public static boolean matches(String glob, String packageName) {
        String prefix = prefixOf(glob);
        if (prefix != null) {
            return packageName.equals(prefix) || packageName.startsWith(prefix + ".");
        }
        return CACHE.computeIfAbsent(glob, PackageGlobs::compile).matcher(packageName).matches();
    }

    /** The package a trailing {@code ..} / {@code .**} refers to, or null when the glob has neither. */
    private static String prefixOf(String glob) {
        if (glob.endsWith("..")) {
            return glob.substring(0, glob.length() - 2);
        }
        if (glob.endsWith(".**")) {
            return glob.substring(0, glob.length() - 3);
        }
        return null;
    }

    private static Pattern compile(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else if (c == '*') {
                regex.append("[^.]+");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    /**
     * The package an import brings in: the leading segments up to the first one that starts with an upper-case
     * letter, which is taken to be the class ({@code a.b.C}, {@code a.b.C.member}, {@code a.b.*} all give {@code a.b}).
     */
    public static String packageOfImport(String importedName) {
        String[] segments = importedName.split("\\.");
        StringBuilder pkg = new StringBuilder();
        for (String segment : segments) {
            if (!segment.isEmpty() && Character.isUpperCase(segment.charAt(0))) {
                break;
            }
            if (pkg.length() > 0) {
                pkg.append('.');
            }
            pkg.append(segment);
        }
        return pkg.toString();
    }
}
