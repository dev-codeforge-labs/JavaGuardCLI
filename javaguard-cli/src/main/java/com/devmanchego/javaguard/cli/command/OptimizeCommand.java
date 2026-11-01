package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.cli.ExitCode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Builds the start-up archive of the packaged distribution in the folder where it lives (see {@link StartupArchive}).
 * Run it once after unzipping, and again after moving the folder; {@code javaguard install} does it too.
 */
@Command(mixinStandardHelpOptions = true, name = "optimize",
        description = "Build the start-up archive (app.jsa) that makes every hook start about 0.4 s faster.")
public final class OptimizeCommand implements Callable<Integer> {

    @Option(names = "--force", description = "Rebuild the archive even when the current one works.")
    private boolean force;

    @Override
    public Integer call() {
        Optional<StartupArchive.Target> target = StartupArchive.target();
        if (target.isEmpty()) {
            System.out.println("javaguard optimize: not running from the packaged javaguard-<version>-all.jar; nothing to do.");
            return ExitCode.SUCCESS.code();
        }
        return ensure(target.get(), force) ? ExitCode.SUCCESS.code() : ExitCode.ENVIRONMENT_ERROR.code();
    }

    /** Builds the archive unless a working one is there; reports on stdout. */
    static boolean ensure(StartupArchive.Target target, boolean rebuild) {
        if (!rebuild && StartupArchive.state(target) == StartupArchive.State.USABLE) {
            System.out.println("javaguard optimize: " + target.archive() + " is up to date.");
            return true;
        }
        System.out.println("javaguard optimize: building the start-up archive (takes a few seconds)...");
        StartupArchive.Result result = StartupArchive.build(target);
        if (result.built()) {
            System.out.println("javaguard optimize: wrote " + result.message());
            return true;
        }
        System.err.println("javaguard optimize: no start-up archive (" + result.message() + "). Hooks still work, "
                + "about 0.4 s slower.");
        return false;
    }
}
