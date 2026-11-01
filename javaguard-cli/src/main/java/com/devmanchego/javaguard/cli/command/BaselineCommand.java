package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.change.ChangeTracker;
import com.devmanchego.javaguard.gate.ConfigGuard;
import com.devmanchego.javaguard.change.GitClient;
import com.devmanchego.javaguard.cli.ExitCode;
import com.devmanchego.javaguard.cli.Main;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Callable;

/** Baseline management (specification &sect;4, &sect;9.1). */
@Command(mixinStandardHelpOptions = true, name = "baseline", description = "Manage per-file baselines used to scope findings to the current session.",
        subcommands = BaselineCommand.ResetCommand.class)
public final class BaselineCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return ExitCode.SUCCESS.code();
    }

    @Command(mixinStandardHelpOptions = true, name = "reset",
            description = "Forget baselines (and the recorded JavaGuard configuration) so the next gate treats current contents as the baseline.")
    static final class ResetCommand implements Callable<Integer> {

        @ParentCommand
        private BaselineCommand parent;

        @Option(names = "--session", description = "Session id to reset. Default: every session.")
        private String session;

        @Override
        public Integer call() {
            JavaGuardPaths paths = new JavaGuardPaths(ProjectRoots.resolve(parent == null ? null : parent.main));
            ChangeTracker tracker = new ChangeTracker(paths, GitClient.none(), Clock.systemUTC());
            try {
                List<String> sessions = session != null ? List.of(session) : tracker.sessions();
                ConfigGuard guard = new ConfigGuard(paths, java.nio.file.Path.of(System.getProperty("user.home")));
                for (String s : sessions) {
                    tracker.resetBaselines(s);
                }
                // The configuration recorded at the start of the session too: what is on disk now becomes the baseline.
                guard.reset(session);
                System.out.println("javaguard baseline reset: " + sessions.size() + " session(s) reset in "
                        + paths.stateDir());
                return ExitCode.SUCCESS.code();
            } catch (IOException e) {
                System.err.println("javaguard baseline reset: " + e.getMessage());
                return ExitCode.ENVIRONMENT_ERROR.code();
            }
        }
    }
}
