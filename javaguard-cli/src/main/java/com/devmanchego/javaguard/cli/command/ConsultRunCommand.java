package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.cli.ExitCode;
import com.devmanchego.javaguard.cli.Main;
import com.devmanchego.javaguard.config.ConfigException;
import com.devmanchego.javaguard.config.LoadedConfig;
import com.devmanchego.javaguard.consult.ConsultStatus;
import com.devmanchego.javaguard.consult.ConsultWorker;
import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.notify.Notifiers;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Callable;

/**
 * Internal: the detached worker of a second-model consultation (specification &sect;15). Started by the loop guard,
 * never by hand; hidden from the help.
 */
@Command(mixinStandardHelpOptions = true, name = "consult-run", hidden = true,
        description = "Internal: run a second-model consultation for a session.")
public final class ConsultRunCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Option(names = "--session", required = true, description = "Session id.")
    private String session;

    @Option(names = "--agent", required = true, description = "Consultant: claude, gemini or codex.")
    private String agent;

    @Override
    public Integer call() {
        AgentKind kind = AgentKind.fromCliName(agent).orElse(null);
        if (kind == null || kind == AgentKind.MANUAL) {
            System.err.println("javaguard consult-run: unknown consultant '" + agent + "'");
            return ExitCode.USAGE_ERROR.code();
        }
        Path root = ProjectRoots.resolve(main);
        LoadedConfig loaded;
        try {
            loaded = ManualConfig.load(main, root);
        } catch (ConfigException e) {
            System.err.println("javaguard consult-run: " + e.getMessage());
            return ExitCode.CONFIG_ERROR.code();
        }
        ConsultStatus status = new ConsultWorker(new JavaGuardPaths(root), loaded.config(), new ProcessRunner(), Clock.systemUTC(),
                ProcessEnvironment.get())
                .notifying(Notifiers.forConfig(loaded.config().notifications(), root, ProcessEnvironment.get())).run(session, kind);
        return status.state() == ConsultStatus.State.DONE ? ExitCode.SUCCESS.code() : ExitCode.ENVIRONMENT_ERROR.code();
    }
}
