package com.devmanchego.javaguard.report;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Fingerprint;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Collapses repetitive {@code javac} output before it reaches the agent
 * (specification &sect;12.2):
 *
 * <ol>
 *   <li>Several "cannot find symbol" errors in the same file, for the same
 *       missing symbol, are one real problem (a missing import). They are
 *       merged into the first occurrence, which records how many followers
 *       were folded into it under {@value #CASCADE_COUNT}.</li>
 *   <li>More than {@value #PER_FILE_THRESHOLD} compile errors in one file
 *       (after step 1) are cut to the first {@value #PER_FILE_KEEP}, replaced
 *       by one synthetic summary finding (marked {@value #SUMMARY}) at the
 *       point of the cut.</li>
 * </ol>
 *
 * <p>Only findings with {@code ruleId.equals("JAVAC")} are affected; every
 * other rule passes through unchanged, in its original position.
 */
public final class CompileErrorCollapser {

    public static final String JAVAC_RULE_ID = "JAVAC";
    public static final String SYMBOL_DETAIL = "symbol";
    public static final String CASCADE_COUNT = "cascadeCount";
    /** Marks a finding as a synthetic summary line, not a real problem to render in full. */
    public static final String SUMMARY = "summary";

    static final int PER_FILE_THRESHOLD = 10;
    static final int PER_FILE_KEEP = 5;

    private CompileErrorCollapser() {
    }

    public static List<Finding> collapse(List<Finding> findings) {
        return capPerFile(collapseCascades(dropByteOrderMarkEchoes(findings)));
    }

    // --- step 0: a byte order mark explains the other errors on line 1 --------

    /**
     * javac rejects a UTF-8 byte order mark ("illegal character" on the mark) and then complains that nothing it reads
     * on that line is a class ("class, interface, enum, or record expected"). The mark is the one problem: the echoes on
     * the same line of the same file would only send the agent looking for a mistake that is not in the code.
     */
    private static List<Finding> dropByteOrderMarkEchoes(List<Finding> findings) {
        java.util.Set<Path> withMark = new java.util.HashSet<>();
        for (Finding f : findings) {
            if (isByteOrderMarkError(f)) {
                f.file().ifPresent(withMark::add);
            }
        }
        if (withMark.isEmpty()) {
            return findings;
        }
        List<Finding> kept = new ArrayList<>();
        for (Finding f : findings) {
            boolean echo = JAVAC_RULE_ID.equals(f.ruleId()) && f.file().filter(withMark::contains).isPresent()
                    && f.line() <= 1 && !isByteOrderMarkError(f);
            if (!echo) {
                kept.add(f);
            }
        }
        return kept;
    }

    private static boolean isByteOrderMarkError(Finding f) {
        return JAVAC_RULE_ID.equals(f.ruleId())
                && (f.message().toLowerCase(java.util.Locale.ROOT).contains("\\ufeff") || f.message().indexOf('\uFEFF') >= 0);
    }

    // --- step 1: same file + same missing symbol -----------------------

    private static List<Finding> collapseCascades(List<Finding> findings) {
        Map<String, Integer> outputIndexByKey = new LinkedHashMap<>();
        List<Finding> result = new ArrayList<>();
        List<Integer> extraCount = new ArrayList<>();

        for (Finding f : findings) {
            Optional<String> key = cascadeKey(f);
            if (key.isEmpty()) {
                result.add(f);
                extraCount.add(0);
                continue;
            }
            Integer existing = outputIndexByKey.get(key.get());
            if (existing == null) {
                outputIndexByKey.put(key.get(), result.size());
                result.add(f);
                extraCount.add(0);
            } else {
                extraCount.set(existing, extraCount.get(existing) + 1);
            }
        }

        for (int i = 0; i < result.size(); i++) {
            if (extraCount.get(i) > 0) {
                result.set(i, withDetail(result.get(i), CASCADE_COUNT, String.valueOf(extraCount.get(i))));
            }
        }
        return result;
    }

    private static Optional<String> cascadeKey(Finding f) {
        if (!JAVAC_RULE_ID.equals(f.ruleId()) || f.file().isEmpty()) {
            return Optional.empty();
        }
        String symbol = f.detail().get(SYMBOL_DETAIL);
        if (symbol == null || symbol.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(f.file().get() + "\u0000" + symbol);
    }

    // --- step 2: more than PER_FILE_THRESHOLD javac findings in one file ---

    private static List<Finding> capPerFile(List<Finding> findings) {
        Map<Path, Long> countByFile = new LinkedHashMap<>();
        for (Finding f : findings) {
            if (JAVAC_RULE_ID.equals(f.ruleId()) && f.file().isPresent()) {
                countByFile.merge(f.file().get(), 1L, Long::sum);
            }
        }

        List<Finding> result = new ArrayList<>();
        Map<Path, Integer> keptSoFar = new LinkedHashMap<>();
        for (Finding f : findings) {
            if (!JAVAC_RULE_ID.equals(f.ruleId()) || f.file().isEmpty()
                    || countByFile.getOrDefault(f.file().get(), 0L) <= PER_FILE_THRESHOLD) {
                result.add(f);
                continue;
            }
            Path file = f.file().get();
            int kept = keptSoFar.merge(file, 1, Integer::sum);
            if (kept <= PER_FILE_KEEP) {
                result.add(f);
            } else if (kept == PER_FILE_KEEP + 1) {
                long dropped = countByFile.get(file) - PER_FILE_KEEP;
                result.add(summaryFinding(f, dropped));
            }
            // kept > PER_FILE_KEEP + 1: dropped silently, already accounted for in the summary
        }
        return result;
    }

    private static Finding summaryFinding(Finding sample, long droppedCount) {
        String message = droppedCount + " more compile error(s) in this file (showing the first " + PER_FILE_KEEP + ").";
        return new Finding(JAVAC_RULE_ID, "Compile errors", Category.COMPILATION, sample.severity(), sample.engine(),
                sample.file(), 0, 0, 0, message, "", Optional.empty(), Map.of(SUMMARY, "true"),
                new Fingerprint(syntheticHex(sample.file().map(Path::toString).orElse("") + "|summary")));
    }

    private static Finding withDetail(Finding f, String key, String value) {
        Map<String, String> detail = new LinkedHashMap<>(f.detail());
        detail.put(key, value);
        return new Finding(f.ruleId(), f.title(), f.category(), f.severity(), f.engine(), f.file(), f.line(),
                f.column(), f.endLine(), f.message(), f.remediation(), f.snippet(), detail, f.fingerprint());
    }

    private static String syntheticHex(String seed) {
        long h = seed.hashCode() & 0xFFFFFFFFL;
        String hex = Long.toHexString(h);
        return hex.isEmpty() ? "0" : hex;
    }
}
