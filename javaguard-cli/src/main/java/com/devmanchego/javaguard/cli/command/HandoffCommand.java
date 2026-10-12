package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.cli.ExitCode;
import com.devmanchego.javaguard.cli.Main;
import com.devmanchego.javaguard.config.ConfigException;
import com.devmanchego.javaguard.config.LoadedConfig;
import com.devmanchego.javaguard.consult.ConsultStatus;
import com.devmanchego.javaguard.consult.ConsultWorker;
import com.devmanchego.javaguard.handoff.SessionHandoff;
import com.devmanchego.javaguard.handoff.SolutionPickup;
import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Generates the handoff document for a session on demand (specification &sect;4, &sect;14); with {@code --consult} it
 * also asks a second model and waits for the answer (&sect;15).
 */
@Command(mixinStandardHelpOptions = true, name = "handoff", description = "Generate the handoff document for a session, optionally consulting a second model.")
public final class HandoffCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Option(names = "--session", description = "Session id. Default: the most recent session.")
    private String session;

    @Option(names = "--consult", description = "Valid values: claude, gemini, codex.")
    private String consult;

    @Override
    public Integer call() {
        AgentKind consultant = null;
        if (consult != null) {
            consultant = AgentKind.fromCliName(consult).filter(k -> k != AgentKind.MANUAL).orElse(null);
            if (consultant == null) {
                System.err.println("javaguard handoff: --consult must be claude, gemini or codex");
                return ExitCode.USAGE_ERROR.code();
            }
        }
        Path root = ProjectRoots.resolve(main);
        LoadedConfig loaded;
        try {
            loaded = ManualConfig.load(main, root);
        } catch (ConfigException e) {
            System.err.println("javaguard handoff: " + e.getMessage());
            return ExitCode.CONFIG_ERROR.code();
        }
        SessionHandoff handoff = new SessionHandoff(new JavaGuardPaths(root), loaded.config(), Clock.systemUTC());
        Optional<String> id = session != null ? Optional.of(session) : handoff.latestSession();
        if (id.isEmpty()) {
            System.err.println("javaguard handoff: no session with recorded gate runs in " + root);
            return ExitCode.USAGE_ERROR.code();
        }
        SessionHandoff.Outcome outcome = handoff.generate(id.get());
        if (outcome.path().isEmpty()) {
            System.err.println("javaguard handoff: " + outcome.message());
            return ExitCode.ENVIRONMENT_ERROR.code();
        }
        System.out.println(outcome.message());
        if (consultant == null) {
            return ExitCode.SUCCESS.code();
        }
        // Asked for by hand: wait for the answer here instead of detaching, whatever consult.enabled says.
        System.out.println("Consulting " + consultant.cliName() + " (up to " + loaded.config().consult().timeoutSeconds() + " s)...");
        ConsultStatus status = new ConsultWorker(new JavaGuardPaths(root), loaded.config(), new ProcessRunner(), Clock.systemUTC(),
                ProcessEnvironment.get()).run(id.get(), consultant);
        if (status.state() != ConsultStatus.State.DONE) {
            System.err.println("javaguard handoff: the consultation failed: " + status.message());
            return ExitCode.ENVIRONMENT_ERROR.code();
        }
        System.out.println("Solution written to .javaguard/handoff/SOLUTION-" + SolutionPickup.shortId(id.get())
                + ".md; the session picks it up on its next gate run.");
        return ExitCode.SUCCESS.code();
    }
}
