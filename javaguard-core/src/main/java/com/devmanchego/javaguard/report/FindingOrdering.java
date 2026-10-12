package com.devmanchego.javaguard.report;

import com.devmanchego.javaguard.model.Finding;

import java.util.Comparator;

/**
 * Ranking used everywhere findings are listed (specification &sect;12.2):
 * severity, then category priority, then file, then line.
 *
 * <p>{@link com.devmanchego.javaguard.model.Severity} and
 * {@link com.devmanchego.javaguard.model.Category} are declared in exactly
 * this priority order, so their natural {@code ordinal()} already ranks
 * correctly; this class exists to keep that fact from being a silent
 * coincidence callers must rediscover.
 */
public final class FindingOrdering {

    public static final Comparator<Finding> BY_RANK = Comparator
            .comparingInt((Finding f) -> f.severity().ordinal())
            .thenComparingInt(f -> f.category().ordinal())
            .thenComparing(f -> f.file().map(java.nio.file.Path::toString).orElse(""))
            .thenComparingInt(Finding::line);

    private FindingOrdering() {
    }
}
