package com.devmanchego.javaguard.analysis.deps;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * CVSS v3.x base score from a vector string ({@code CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H}); OSV publishes the
 * vector, not the number, and JG-DEP-002's severity is decided by the number (specification &sect;11.4). Temporal and
 * environmental metrics in the vector are ignored. Empty for anything that is not a complete v3 vector (including v4).
 */
public final class CvssV3 {

    private CvssV3() {
    }

    public static OptionalDouble baseScore(String vector) {
        if (vector == null || !vector.startsWith("CVSS:3.")) {
            return OptionalDouble.empty();
        }
        Map<String, String> metrics = new HashMap<>();
        for (String part : vector.split("/")) {
            int colon = part.indexOf(':');
            if (colon > 0) {
                metrics.put(part.substring(0, colon), part.substring(colon + 1));
            }
        }
        boolean changed;
        switch (metrics.getOrDefault("S", "")) {
            case "U" -> changed = false;
            case "C" -> changed = true;
            default -> {
                return OptionalDouble.empty();
            }
        }
        Double av = lookup(metrics.get("AV"), Map.of("N", 0.85, "A", 0.62, "L", 0.55, "P", 0.2));
        Double ac = lookup(metrics.get("AC"), Map.of("L", 0.77, "H", 0.44));
        Double pr = lookup(metrics.get("PR"), changed ? Map.of("N", 0.85, "L", 0.68, "H", 0.5) : Map.of("N", 0.85, "L", 0.62, "H", 0.27));
        Double ui = lookup(metrics.get("UI"), Map.of("N", 0.85, "R", 0.62));
        Map<String, Double> impact = Map.of("H", 0.56, "L", 0.22, "N", 0.0);
        Double c = lookup(metrics.get("C"), impact);
        Double i = lookup(metrics.get("I"), impact);
        Double a = lookup(metrics.get("A"), impact);
        if (av == null || ac == null || pr == null || ui == null || c == null || i == null || a == null) {
            return OptionalDouble.empty();
        }
        double iss = 1 - (1 - c) * (1 - i) * (1 - a);
        double impactScore = changed ? 7.52 * (iss - 0.029) - 3.25 * Math.pow(iss - 0.02, 15) : 6.42 * iss;
        if (impactScore <= 0) {
            return OptionalDouble.of(0.0);
        }
        double exploitability = 8.22 * av * ac * pr * ui;
        double raw = changed ? 1.08 * (impactScore + exploitability) : impactScore + exploitability;
        return OptionalDouble.of(roundUp(Math.min(raw, 10)));
    }

    private static Double lookup(String value, Map<String, Double> table) {
        return value == null ? null : table.get(value);
    }

    /** The specification's Roundup: the smallest number with one decimal that is at least the input (v3.1 integer form). */
    private static double roundUp(double input) {
        long integer = Math.round(input * 100_000);
        return integer % 10_000 == 0 ? integer / 100_000.0 : (Math.floor(integer / 10_000.0) + 1) / 10.0;
    }
}
