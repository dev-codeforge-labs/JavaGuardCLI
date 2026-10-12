package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.cli.Main;
import com.devmanchego.javaguard.config.ConfigException;
import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.config.LoadedConfig;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Configuration loading for manual commands. Unlike hook mode, which falls back to defaults so the
 * agent is never blocked, an invalid configuration is an error here (specification &sect;18.1, exit 3).
 */
final class ManualConfig {

    private ManualConfig() {
    }

    static LoadedConfig load(Main main, Path root) throws ConfigException {
        Optional<Path> explicitConfig = Optional.ofNullable(main == null ? null : main.configFile);
        return ConfigLoader.load(ConfigSources.standard(root, explicitConfig,
                Path.of(System.getProperty("user.home")), ProcessEnvironment.get()));
    }
}
