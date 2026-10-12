package com.devmanchego.javaguard.analysis.deps;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orders dependency versions well enough to tell a downgrade from an upgrade: dot, dash and underscore separated
 * segments, numbers compared as numbers, a missing segment as zero, and a qualifier ({@code alpha}, {@code beta},
 * {@code rc}, {@code SNAPSHOT}, ...) as older than the same version without one. Not Maven's full algorithm, on purpose:
 * only a clear downgrade should be reported.
 */
final class VersionComparator {

    private static final Pattern SEGMENT = Pattern.compile("\\d+|[A-Za-z]+");

    private VersionComparator() {
    }

    static int compare(String left, String right) {
        List<String> a = segments(left);
        List<String> b = segments(right);
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            String x = i < a.size() ? a.get(i) : null;
            String y = i < b.size() ? b.get(i) : null;
            int order = compareSegment(x, y);
            if (order != 0) {
                return order;
            }
        }
        return 0;
    }

    private static int compareSegment(String x, String y) {
        if (x == null || y == null) {
            String present = x == null ? y : x;
            int sign = x == null ? -1 : 1; // x missing: right has more
            if (isNumber(present)) {
                return Long.parseLong(present) == 0 ? 0 : sign;
            }
            // A trailing qualifier makes the version older (1.0-rc1 < 1.0), unless it is a release marker.
            return isReleaseMarker(present) ? 0 : -sign;
        }
        if (isNumber(x) && isNumber(y)) {
            return Long.compare(Long.parseLong(x), Long.parseLong(y));
        }
        if (isNumber(x)) {
            return 1; // 1.0.1 > 1.0.rc
        }
        if (isNumber(y)) {
            return -1;
        }
        return Integer.compare(rank(x), rank(y));
    }

    private static List<String> segments(String version) {
        List<String> out = new ArrayList<>();
        Matcher m = SEGMENT.matcher(version);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static boolean isNumber(String segment) {
        return !segment.isEmpty() && Character.isDigit(segment.charAt(0));
    }

    private static boolean isReleaseMarker(String segment) {
        String s = segment.toLowerCase(Locale.ROOT);
        return s.equals("final") || s.equals("release") || s.equals("ga") || s.equals("jre") || s.equals("v");
    }

    private static int rank(String qualifier) {
        return switch (qualifier.toLowerCase(Locale.ROOT)) {
            case "snapshot" -> 0;
            case "alpha", "a" -> 1;
            case "beta", "b" -> 2;
            case "milestone", "m" -> 3;
            case "rc", "cr" -> 4;
            case "final", "release", "ga", "jre" -> 6;
            default -> 5;
        };
    }
}
