package com.devmanchego.javaguard.loop;

import com.devmanchego.javaguard.change.Hashing;
import com.devmanchego.javaguard.model.GateKind;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * What the detectors need to know about one gate run (specification &sect;13.2).
 *
 * @param run            the session's run number, increasing
 * @param blocked        the run's verdict was BLOCK (before escalation)
 * @param fingerprints   fingerprints of the blocking findings
 * @param blockingCount  number of blocking findings
 * @param fileHashes     content hash of each file the run looked at, after the agent's change
 * @param stopHookActive the agent said it is already continuing because of an earlier end-of-turn block
 */
public record RunRecord(long run, GateKind gate, boolean blocked, List<String> fingerprints, int blockingCount,
                        Map<String, String> fileHashes, boolean stopHookActive) {

    public RunRecord {
        Objects.requireNonNull(gate, "gate");
        fingerprints = List.copyOf(fingerprints);
        fileHashes = Map.copyOf(fileHashes);
    }

    /** The blocking signature: a hash of the sorted set of blocking fingerprints, empty when nothing blocks. */
    public String signature() {
        if (fingerprints.isEmpty()) {
            return "";
        }
        return Hashing.shortSha256(String.join(",", new TreeSet<>(fingerprints)), 16);
    }
}
