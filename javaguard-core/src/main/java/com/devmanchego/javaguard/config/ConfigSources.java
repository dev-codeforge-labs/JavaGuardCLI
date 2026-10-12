package com.devmanchego.javaguard.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Where configuration comes from, lowest to highest precedence
 * (specification &sect;18.1): built-in defaults, user file, project file,
 * environment ({@code JAVAGUARD_<SECTION>_<KEY>}), CLI overrides.
 *
 * @param userFile     {@code ~/.javaguard/config.yml}; skipped when missing
 * @param projectFile  {@code <root>/javaguard.yml}, or the {@code --config} file; skipped when missing,
 *                     except that an explicit {@code --config} file must exist
 * @param explicit     true when {@code projectFile} came from {@code --config}
 * @param environment  process environment
 * @param cliOverrides dotted key path to value, e.g. {@code fastGate.budgetMillis -> 5000}
 */
public record ConfigSources(
        Optional<Path> userFile,
        Optional<Path> projectFile,
        boolean explicit,
        Map<String, String> environment,
        Map<String, String> cliOverrides
) {

    public static final String PROJECT_FILE_NAME = "javaguard.yml";

    public ConfigSources {
        Objects.requireNonNull(userFile, "userFile");
        Objects.requireNonNull(projectFile, "projectFile");
        environment = Map.copyOf(environment);
        cliOverrides = Map.copyOf(cliOverrides);
    }

    /** Defaults only: no files, no environment, no overrides. */
    public static ConfigSources defaultsOnly() {
        return new ConfigSources(Optional.empty(), Optional.empty(), false, Map.of(), Map.of());
    }

    /**
     * The standard lookup for a project.
     *
     * @param projectRoot    project root
     * @param explicitConfig {@code --config} value, if given
     * @param userHome       user home directory
     * @param environment    process environment
     */
    public static ConfigSources standard(Path projectRoot, Optional<Path> explicitConfig, Path userHome,
                                         Map<String, String> environment) {
        Path userFile = userHome.resolve(".javaguard").resolve("config.yml");
        Path projectFile = explicitConfig.orElse(projectRoot.resolve(PROJECT_FILE_NAME));
        return new ConfigSources(Optional.of(userFile), Optional.of(projectFile), explicitConfig.isPresent(),
                environment, Map.of());
    }
}
