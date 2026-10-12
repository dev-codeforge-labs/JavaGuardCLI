package com.devmanchego.javaguard.consult;

import com.devmanchego.javaguard.state.JavaGuardPaths;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Runs the consultation in a separate JavaGuard process ({@code javaguard consult-run}) that outlives the hook:
 * the worker waits for the second model, enforces the timeout and writes the solution file. The worker's output goes to
 * {@code .javaguard/logs/consult-<session>.log}; its input is empty.
 */
public final class DetachedConsultRunner implements ConsultRunner {

    private static final String MAIN_CLASS = "com.devmanchego.javaguard.cli.Main";

    private final Supplier<List<String>> launcherPrefix;

    public DetachedConsultRunner() {
        this(DetachedConsultRunner::currentLauncher);
    }

    /** {@code launcherPrefix} is the command that runs this program, to which the subcommand is appended. */
    public DetachedConsultRunner(Supplier<List<String>> launcherPrefix) {
        this.launcherPrefix = launcherPrefix;
    }

    @Override
    public void start(Launch launch) throws IOException {
        JavaGuardPaths paths = new JavaGuardPaths(launch.projectRoot());
        Files.createDirectories(paths.logsDir());
        List<String> command = new ArrayList<>(launcherPrefix.get());
        command.addAll(List.of("--project-dir", launch.projectRoot().toString(), "consult-run", "--session", launch.sessionId(),
                "--agent", launch.agent().cliName()));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(launch.projectRoot().toFile());
        builder.redirectErrorStream(true);
        builder.redirectInput(ProcessBuilder.Redirect.from(new File(System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .contains("win") ? "NUL" : "/dev/null")));
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(paths.logsDir()
                .resolve("consult-" + com.devmanchego.javaguard.handoff.SolutionPickup.shortId(launch.sessionId()) + ".log").toFile()));
        builder.start();
    }

    /** The packaged executable when that is what runs, else this JVM with its class path. */
    private static List<String> currentLauncher() {
        String self = ProcessHandle.current().info().command().orElse("java");
        String base = Path.of(self).getFileName().toString().toLowerCase(Locale.ROOT);
        if (base.startsWith("javaguard")) {
            return List.of(self);
        }
        return List.of(self, "-cp", System.getProperty("java.class.path"), MAIN_CLASS);
    }
}
