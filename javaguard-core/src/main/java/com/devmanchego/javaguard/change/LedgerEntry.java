package com.devmanchego.javaguard.change;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One file touched in a session (specification &sect;9.4). Timestamps are
 * ISO-8601 strings, hashes SHA-256 hex; {@code lastSeenHash} is null when the
 * file does not exist (deleted).
 *
 * @param firstTouched  when the session first touched the file
 * @param baselineSource where the baseline came from
 * @param approximate   true for {@link BaselineSource#GIT} (see there)
 * @param baselineHash  SHA-256 of the stored baseline bytes
 * @param baselineFile  baseline file name inside {@code state/<session>/baseline/}
 * @param lastSeenHash  SHA-256 of the file when last seen by a gate; null if absent
 * @param deleted       true when the file no longer exists
 * @param gateRuns      recent gate runs that included the file ({@code "<instant> <gate>"}), capped
 */
public record LedgerEntry(
        String firstTouched,
        BaselineSource baselineSource,
        boolean approximate,
        String baselineHash,
        String baselineFile,
        String lastSeenHash,
        boolean deleted,
        List<String> gateRuns
) {

    /** Older runs are dropped beyond this many, so the ledger cannot grow without bound. */
    static final int MAX_GATE_RUNS = 50;

    public LedgerEntry {
        Objects.requireNonNull(firstTouched, "firstTouched");
        Objects.requireNonNull(baselineSource, "baselineSource");
        Objects.requireNonNull(baselineHash, "baselineHash");
        Objects.requireNonNull(baselineFile, "baselineFile");
        gateRuns = List.copyOf(gateRuns == null ? List.of() : gateRuns);
    }

    LedgerEntry seen(String hash, boolean isDeleted, String gateRun) {
        List<String> runs = new ArrayList<>(gateRuns);
        runs.add(gateRun);
        if (runs.size() > MAX_GATE_RUNS) {
            runs = runs.subList(runs.size() - MAX_GATE_RUNS, runs.size());
        }
        return new LedgerEntry(firstTouched, baselineSource, approximate, baselineHash, baselineFile, hash, isDeleted,
                runs);
    }
}
