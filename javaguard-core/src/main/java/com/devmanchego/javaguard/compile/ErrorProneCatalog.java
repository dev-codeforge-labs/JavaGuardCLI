package com.devmanchego.javaguard.compile;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The curated Error Prone check list (catalog &sect;2): every ERROR-severity
 * check stays enabled at its Error Prone default (mapped to
 * {@link Severity#CRITICAL} here); every WARNING/SUGGESTION-severity check is
 * disabled ({@code -XepDisableAllWarnings}) except the ones explicitly
 * listed below, re-enabled one by one ({@code -Xep:<Check>:WARN}) at the
 * catalog's severity.
 *
 * <p>The catalog does not assign a {@link Category} per Error Prone check
 * (only per severity); the handful with an obvious better fit are listed in
 * {@link #CATEGORY_OVERRIDE} (matching the catalog's own JG rule overlaps,
 * e.g. {@code UnusedVariable}/{@code UnusedMethod} &harr; JG-AI-007), and
 * every other check - including every ERROR-severity one - defaults to
 * {@link Category#RELIABILITY}, since Error Prone's error-level checks are
 * overwhelmingly correctness bugs (`DeadException`, `FormatString`,
 * `ReturnValueIgnored`, `SelfAssignment`, `ArrayEquals`, ...). A full
 * per-check category table is future work if this default proves too coarse.
 */
final class ErrorProneCatalog {

    private static final Map<String, Severity> SEVERITY_OVERRIDE = Map.ofEntries(
            Map.entry("BigDecimalEquals", Severity.MAJOR),
            Map.entry("BoxedPrimitiveEquality", Severity.MAJOR),
            Map.entry("ReferenceEquality", Severity.MAJOR),
            Map.entry("EqualsHashCode", Severity.MAJOR),
            Map.entry("FutureReturnValueIgnored", Severity.MAJOR),
            Map.entry("CatchAndPrintStackTrace", Severity.MAJOR),
            Map.entry("JavaTimeDefaultTimeZone", Severity.MAJOR),
            Map.entry("DefaultCharset", Severity.MAJOR),
            Map.entry("StreamResourceLeak", Severity.MAJOR),
            Map.entry("ThreadPriorityCheck", Severity.MAJOR),
            Map.entry("WaitNotInLoop", Severity.MAJOR),
            Map.entry("SynchronizeOnNonFinalField", Severity.MAJOR),
            Map.entry("DoubleBraceInitialization", Severity.MAJOR),
            Map.entry("ObjectToString", Severity.MAJOR),
            Map.entry("InconsistentCapitalization", Severity.INFO),
            Map.entry("UnusedVariable", Severity.INFO),
            Map.entry("UnusedMethod", Severity.INFO),
            Map.entry("MissingCasesInEnumSwitch", Severity.INFO),
            Map.entry("OperatorPrecedence", Severity.INFO),
            Map.entry("StringSplitter", Severity.INFO),
            // Phase 24
            Map.entry("DefaultLocale", Severity.INFO), // Phase 26 corpus: mostly numeric date patterns, where the locale is irrelevant
            Map.entry("StringCaseLocaleUsage", Severity.MAJOR),
            Map.entry("IntLongMath", Severity.MAJOR),
            Map.entry("NarrowingCompoundAssignment", Severity.MAJOR),
            Map.entry("UnsynchronizedOverridesSynchronized", Severity.MAJOR),
            Map.entry("ModifiedButNotUsed", Severity.INFO));

    private static final Map<String, Category> CATEGORY_OVERRIDE = Map.of(
            "UnusedVariable", Category.AI_DEBRIS,
            "UnusedMethod", Category.AI_DEBRIS,
            "CatchAndPrintStackTrace", Category.AI_DEBRIS,
            "FutureReturnValueIgnored", Category.CONCURRENCY,
            "ThreadPriorityCheck", Category.CONCURRENCY,
            "WaitNotInLoop", Category.CONCURRENCY,
            "SynchronizeOnNonFinalField", Category.CONCURRENCY,
            "UnsynchronizedOverridesSynchronized", Category.CONCURRENCY,
            "ModifiedButNotUsed", Category.AI_DEBRIS);

    private ErrorProneCatalog() {
    }

    /** {@code -XepDisableAllWarnings} plus a {@code -Xep:<Check>:WARN} for each re-enabled warning-level check. */
    static List<String> javacOptions() {
        List<String> options = new ArrayList<>();
        options.add("-XepDisableAllWarnings");
        SEVERITY_OVERRIDE.keySet().forEach(check -> options.add("-Xep:" + check + ":WARN"));
        return options;
    }

    /** {@link Severity#CRITICAL} for any check not in the curated warning list (i.e. every enabled ERROR check). */
    static Severity severityOf(String checkName) {
        return SEVERITY_OVERRIDE.getOrDefault(checkName, Severity.CRITICAL);
    }

    static Category categoryOf(String checkName) {
        return CATEGORY_OVERRIDE.getOrDefault(checkName, Category.RELIABILITY);
    }
}
