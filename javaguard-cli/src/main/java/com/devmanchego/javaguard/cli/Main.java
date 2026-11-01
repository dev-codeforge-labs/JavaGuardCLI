package com.devmanchego.javaguard.cli;

import com.devmanchego.javaguard.cli.command.BaselineCommand;
import com.devmanchego.javaguard.cli.command.CaptureCommand;
import com.devmanchego.javaguard.cli.command.CdsTrainCommand;
import com.devmanchego.javaguard.cli.command.CheckCommand;
import com.devmanchego.javaguard.cli.command.ConsultRunCommand;
import com.devmanchego.javaguard.cli.command.DoctorCommand;
import com.devmanchego.javaguard.cli.command.HandoffCommand;
import com.devmanchego.javaguard.cli.command.HookCommand;
import com.devmanchego.javaguard.cli.command.InstallCommand;
import com.devmanchego.javaguard.cli.command.OptimizeCommand;
import com.devmanchego.javaguard.cli.command.RulesCommand;
import com.devmanchego.javaguard.cli.command.StateCommand;
import com.devmanchego.javaguard.cli.command.UninstallCommand;
import com.devmanchego.javaguard.cli.command.WarmupCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * JavaGuardCLI entry point (specification &sect;4).
 *
 * <p>Global options declared here use {@link CommandLine.ScopeType#INHERIT}
 * so every subcommand receives them without redeclaring them.
 */
@Command(
        name = "javaguard",
        mixinStandardHelpOptions = true,
        versionProvider = Main.VersionProvider.class,
        description = "A quality gate for AI coding agents working on Java code.",
        subcommands = {
                HookCommand.class,
                CheckCommand.class,
                InstallCommand.class,
                UninstallCommand.class,
                DoctorCommand.class,
                WarmupCommand.class,
                OptimizeCommand.class,
                CdsTrainCommand.class,
                CaptureCommand.class,
                HandoffCommand.class,
                RulesCommand.class,
                StateCommand.class,
                BaselineCommand.class,
                ConsultRunCommand.class,
        }
)
public final class Main implements Callable<Integer> {

    @Option(names = "--project-dir", scope = CommandLine.ScopeType.INHERIT,
            description = "Project root. Default: auto-detected (specification section 7).")
    public Path projectDir;

    @Option(names = "--config", scope = CommandLine.ScopeType.INHERIT,
            description = "Path to a javaguard.yml file, overriding the default lookup (specification section 18.1).")
    public Path configFile;

    @Option(names = "--verbose", scope = CommandLine.ScopeType.INHERIT,
            description = "Log at FINE level to .javaguard/logs/javaguard.log. Never affects stdout in hook mode.")
    public boolean verbose;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main())
                .setExecutionExceptionHandler(new FailOpenTopLevelHandler())
                .execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        // No subcommand given: behave like --help.
        CommandLine.usage(this, System.out);
        return ExitCode.SUCCESS.code();
    }

    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String version = Main.class.getPackage().getImplementationVersion();
            return new String[] { "javaguard " + (version != null ? version : "1.0.0") };
        }
    }

    /**
     * Last-resort safety net for manual (non-hook) commands: an uncaught
     * exception is reported on stderr as {@link ExitCode#INTERNAL_ERROR},
     * never as a raw stack trace dump. {@code hook} does not rely on this; it
     * has its own {@link FailOpenHookExecutor} because hook mode must exit 0,
     * not 5.
     */
    static final class FailOpenTopLevelHandler implements CommandLine.IExecutionExceptionHandler {
        @Override
        public int handleExecutionException(Exception ex, CommandLine commandLine, CommandLine.ParseResult parseResult) {
            System.err.println("javaguard: internal error: " + ex.getMessage());
            return ExitCode.INTERNAL_ERROR.code();
        }
    }
}
