package com.devmanchego.javaguard.config;

import java.util.List;
import java.util.Objects;

/**
 * A validated configuration plus non-fatal warnings (unknown keys,
 * unmatched environment variables), which {@code doctor} and the log report.
 */
public record LoadedConfig(JavaGuardConfig config, List<String> warnings) {

    public LoadedConfig {
        Objects.requireNonNull(config, "config");
        warnings = List.copyOf(warnings);
    }
}
