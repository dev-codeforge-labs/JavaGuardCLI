package com.devmanchego.javaguard.loop;

import com.devmanchego.javaguard.config.JavaGuardConfig;

/**
 * The thresholds of the detectors (specification &sect;13.3, configuration &sect;18.2 {@code loop.*} and
 * {@code fullGate.maxConsecutiveBlocks}).
 */
public record LoopSettings(int repeatThreshold, int oscillationWindow, int noProgressWindow, int maxBlockedRuns,
                           int maxConsecutiveBlocks) {

    public static LoopSettings of(JavaGuardConfig config) {
        return new LoopSettings(config.loop().repeatThreshold(), config.loop().oscillationWindow(),
                config.loop().noProgressWindow(), config.loop().maxBlockedRuns(), config.fullGate().maxConsecutiveBlocks());
    }
}
