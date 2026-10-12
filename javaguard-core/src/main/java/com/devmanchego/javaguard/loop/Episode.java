package com.devmanchego.javaguard.loop;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Set;

/**
 * One stretch of blocked runs of a gate kind that are about the same problem, and how far up the ladder it has
 * climbed (specification &sect;13.3). An episode starts with a blocked run; it ends with a run where nothing
 * blocks, with a run whose blocking set shares no fingerprint with the previous one (a different problem), or
 * with a HALT.
 *
 * @param level             0 to 3; never decreases within the episode (a consumed solution sets it to 1)
 * @param fingerprints      blocking fingerprints of the episode's latest run
 * @param blockedRuns       blocked runs so far in the episode
 * @param startRun          run number the episode started at
 * @param lastEscalationRun run number of the latest escalation; detectors only look at later runs
 * @param lastDetector      the detector behind the latest escalation, empty before any
 * @param consultation      state of the second-model consultation requested at level 2
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Episode(int level, Set<String> fingerprints, int blockedRuns, long startRun, long lastEscalationRun,
                      String lastDetector, Consultation consultation) {

    public enum Consultation { NONE, REQUESTED, FAILED }

    public Episode {
        fingerprints = fingerprints == null ? Set.of() : Set.copyOf(fingerprints);
        lastDetector = lastDetector == null ? "" : lastDetector;
        consultation = consultation == null ? Consultation.NONE : consultation;
    }

    /** No episode in progress. */
    public static Episode none() {
        return new Episode(0, Set.of(), 0, 0, 0, "", Consultation.NONE);
    }

    public boolean active() {
        return blockedRuns > 0;
    }
}
