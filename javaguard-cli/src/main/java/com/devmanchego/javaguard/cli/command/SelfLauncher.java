package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.cli.Main;
import com.devmanchego.javaguard.install.ClaudeHookConfig;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.jar.JarFile;

/**
 * How an agent's hook configuration should launch this program (contracts &sect;3.4): the packaged
 * {@code javaguard.exe} when that is what is running, a self-contained (shaded) JAR through the
 * current {@code java}, or an explicit {@code --command}. Anything else cannot be launched from a
 * hook, so the caller must be told instead of writing a command that cannot work.
 */
final class SelfLauncher {

    private SelfLauncher() {
    }

    /** Quoted launcher prefix for {@code explicitCommand}, or auto-detected when it is null/blank. */
    static Optional<String> resolve(String explicitCommand) {
        if (explicitCommand != null && !explicitCommand.isBlank()) {
            return Optional.of(ClaudeHookConfig.quoteExecutable(explicitCommand.strip()));
        }
        Optional<Path> self = ProcessHandle.current().info().command().map(Path::of);
        if (self.isPresent() && baseName(self.get()).equals("javaguard")) {
            return Optional.of(ClaudeHookConfig.quoteExecutable(self.get().toString()));
        }
        Optional<Path> jar = selfContainedJar();
        if (jar.isPresent() && self.isPresent()) {
            return Optional.of(ClaudeHookConfig.quoteExecutable(self.get().toString()) + " -jar "
                    + ClaudeHookConfig.quoteExecutable(jar.get().toString()));
        }
        return Optional.empty();
    }

    private static String baseName(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** The JAR this class was loaded from, if it bundles its own dependencies (picocli is the probe). */
    private static Optional<Path> selfContainedJar() {
        try {
            Path location = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(location) || !location.toString().endsWith(".jar")) {
                return Optional.empty();
            }
            try (JarFile jar = new JarFile(location.toFile())) {
                return jar.getEntry("picocli/CommandLine.class") != null ? Optional.of(location) : Optional.empty();
            }
        } catch (URISyntaxException | IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
