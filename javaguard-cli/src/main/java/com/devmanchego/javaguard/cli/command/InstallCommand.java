package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.cli.ExitCode;
import com.devmanchego.javaguard.cli.Main;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.install.AgentHookConfig;
import com.devmanchego.javaguard.install.ClaudeHookConfig;
import com.devmanchego.javaguard.install.ClaudeHookConfig.Gate;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Writes/merges hook configuration for an agent (specification &sect;4, contracts &sect;3.4/4.4/5.4).
 * Claude Code, Gemini CLI and Codex.
 *
 * <p>Existing settings are merged, never replaced; the file is backed up first, and a file that is
 * not valid JSON is left untouched.
 */
@Command(mixinStandardHelpOptions = true, name = "install", description = "Install JavaGuard hooks into an AI coding CLI's configuration.")
public final class InstallCommand implements Callable<Integer> {

    static final String CONFIG_TEMPLATE = """
            # JavaGuardCLI configuration. Every key is optional; built-in defaults apply.
            # Reference: .private/spec/01-SPECIFICATION.md, section 18.2.
            #
            # exclude: ["**/legacy/**"]
            # rules:
            #   JG-PERF-005: { enabled: false }
            #   JG-AI-001:   { severity: INFO }
            """;

    @ParentCommand
    private Main main;

    @Option(names = "--agent", required = true, description = "Valid values: claude, gemini, codex, all.")
    private String agent;

    @Option(names = "--scope", defaultValue = "project", description = "Valid values: project, user.")
    private String scope;

    @Option(names = "--pre-edit", defaultValue = "auto",
            description = "Valid values: auto (only for projects without git), always, never.")
    private String preEdit;

    @Option(names = "--dry-run", description = "Print the resulting configuration instead of writing it.")
    private boolean dryRun;

    @Option(names = "--command",
            description = "Path of the javaguard executable to write into the hook command. "
                    + "Default: the running executable, or the running self-contained JAR.")
    private String command;

    @Override
    public Integer call() {
        Optional<String> invalid = validate();
        if (invalid.isPresent()) {
            System.err.println("javaguard install: " + invalid.get());
            return ExitCode.USAGE_ERROR.code();
        }
        String which = agent.toLowerCase();
        List<String> agents = which.equals("all") ? List.of("claude", "gemini", "codex") : List.of(which);
        int result = ExitCode.SUCCESS.code();
        for (String name : agents) {
            int exit = switch (name) {
                case "claude" -> installClaude();
                case "gemini" -> installOther(AgentHookConfig.Agent.GEMINI);
                default -> installOther(AgentHookConfig.Agent.CODEX);
            };
            if (result == ExitCode.SUCCESS.code()) {
                result = exit;
            }
        }
        if (result == ExitCode.SUCCESS.code() && !dryRun) {
            System.out.println("Next: run 'javaguard warmup' to build the classpath cache so edits are compiled, "
                    + "then 'javaguard doctor'.");
        }
        return result;
    }

    private Optional<String> validate() {
        if (!List.of("claude", "gemini", "codex", "all").contains(agent.toLowerCase())) {
            return Optional.of("unknown --agent '" + agent + "' (valid: claude, gemini, codex, all)");
        }
        if (!List.of("project", "user").contains(scope.toLowerCase())) {
            return Optional.of("unknown --scope '" + scope + "' (valid: project, user)");
        }
        if (!List.of("auto", "always", "never").contains(preEdit.toLowerCase())) {
            return Optional.of("unknown --pre-edit '" + preEdit + "' (valid: auto, always, never)");
        }
        return Optional.empty();
    }

    private int installClaude() {
        Path root = ProjectRoots.resolve(main);
        Optional<String> launcher = SelfLauncher.resolve(command);
        if (launcher.isEmpty()) {
            System.err.println("javaguard install: cannot tell how to launch javaguard from a hook (not running as "
                    + "javaguard.exe or from a self-contained JAR). Pass --command <path-to-javaguard>.");
            return ExitCode.ENVIRONMENT_ERROR.code();
        }
        ClaudeSettingsFile settingsFile = scope.equalsIgnoreCase("user")
                ? ClaudeSettingsFile.userScope(Path.of(System.getProperty("user.home")))
                : ClaudeSettingsFile.projectScope(root);

        ObjectNode current;
        try {
            current = settingsFile.read();
        } catch (ClaudeSettingsFile.UnreadableException e) {
            System.err.println("javaguard install: " + e.getMessage());
            return ExitCode.CONFIG_ERROR.code();
        } catch (IOException e) {
            System.err.println("javaguard install: cannot read " + settingsFile.path() + ": " + e.getMessage());
            return ExitCode.ENVIRONMENT_ERROR.code();
        }

        List<Gate> ensure = new ArrayList<>(List.of(Gate.POST_EDIT, Gate.END_OF_TURN, Gate.TURN_START));
        List<Gate> remove = new ArrayList<>();
        switch (preEdit.toLowerCase()) {
            case "always" -> ensure.add(Gate.PRE_EDIT);
            case "never" -> remove.add(Gate.PRE_EDIT);
            default -> {
                // auto: PRE_EDIT only snapshots, which git already does for tracked files (spec section 9.1).
                if (!insideGitRepository(root)) {
                    ensure.add(Gate.PRE_EDIT);
                }
            }
        }
        ObjectNode updated = ClaudeHookConfig.install(current, launcher.get(), ensure, remove);

        if (dryRun) {
            System.out.print(ClaudeHookConfig.render(updated));
            return ExitCode.SUCCESS.code();
        }
        try {
            if (updated.equals(current) && settingsFile.exists()) {
                System.out.println("javaguard install: " + settingsFile.path() + " is already up to date.");
            } else {
                Optional<Path> backup = settingsFile.write(updated, Instant.now());
                System.out.println("javaguard install: wrote Claude Code hooks to " + settingsFile.path());
                backup.ifPresent(b -> System.out.println("  previous file saved as " + b));
            }
            prepareProject(root);
        } catch (IOException e) {
            System.err.println("javaguard install: " + e.getMessage());
            return ExitCode.ENVIRONMENT_ERROR.code();
        }
        return ExitCode.SUCCESS.code();
    }

    /** Gemini CLI ({@code .gemini/settings.json}) and Codex ({@code .codex/hooks.json}); contracts section 4.4 and 5.4. */
    private int installOther(AgentHookConfig.Agent which) {
        Path root = ProjectRoots.resolve(main);
        Optional<String> launcher = SelfLauncher.resolve(command);
        if (launcher.isEmpty()) {
            System.err.println("javaguard install: cannot tell how to launch javaguard from a hook (not running as "
                    + "javaguard.exe or from a self-contained JAR). Pass --command <path-to-javaguard>.");
            return ExitCode.ENVIRONMENT_ERROR.code();
        }
        ClaudeSettingsFile file = hookFile(which, root);
        String label = which == AgentHookConfig.Agent.GEMINI ? "Gemini CLI" : "Codex";

        ObjectNode current;
        try {
            current = file.read();
        } catch (ClaudeSettingsFile.UnreadableException e) {
            System.err.println("javaguard install: " + e.getMessage());
            return ExitCode.CONFIG_ERROR.code();
        } catch (IOException e) {
            System.err.println("javaguard install: cannot read " + file.path() + ": " + e.getMessage());
            return ExitCode.ENVIRONMENT_ERROR.code();
        }
        List<AgentHookConfig.Gate> ensure = new ArrayList<>(List.of(AgentHookConfig.Gate.POST_EDIT, AgentHookConfig.Gate.END_OF_TURN,
                AgentHookConfig.Gate.TURN_START));
        List<AgentHookConfig.Gate> remove = new ArrayList<>();
        switch (preEdit.toLowerCase()) {
            case "always" -> ensure.add(AgentHookConfig.Gate.PRE_EDIT);
            case "never" -> remove.add(AgentHookConfig.Gate.PRE_EDIT);
            default -> {
                if (!insideGitRepository(root)) {
                    ensure.add(AgentHookConfig.Gate.PRE_EDIT);
                }
            }
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        ObjectNode updated = AgentHookConfig.install(current, which, AgentHookConfig.launcherFor(which, launcher.get(), windows),
                which == AgentHookConfig.Agent.CODEX && windows
                        ? Optional.of(AgentHookConfig.launcherFor(which, launcher.get(), true)) : Optional.empty(),
                ensure, remove);
        if (dryRun) {
            System.out.print(ClaudeHookConfig.render(updated));
            return ExitCode.SUCCESS.code();
        }
        try {
            if (updated.equals(current) && file.exists()) {
                System.out.println("javaguard install: " + file.path() + " is already up to date.");
            } else {
                Optional<Path> backup = file.write(updated, Instant.now());
                System.out.println("javaguard install: wrote " + label + " hooks to " + file.path());
                backup.ifPresent(b -> System.out.println("  previous file saved as " + b));
            }
            prepareProject(root);
        } catch (IOException e) {
            System.err.println("javaguard install: " + e.getMessage());
            return ExitCode.ENVIRONMENT_ERROR.code();
        }
        return ExitCode.SUCCESS.code();
    }

    private ClaudeSettingsFile hookFile(AgentHookConfig.Agent which, Path root) {
        Path base = scope.equalsIgnoreCase("user") ? Path.of(System.getProperty("user.home")) : root;
        return which == AgentHookConfig.Agent.GEMINI
                ? new ClaudeSettingsFile(base.resolve(".gemini").resolve("settings.json"))
                : new ClaudeSettingsFile(base.resolve(".codex").resolve("hooks.json"));
    }

    /** State directory with its self-ignore, and a commented {@code javaguard.yml} when none exists. */
    private static void prepareProject(Path root) throws IOException {
        new JavaGuardPaths(root).ensureLayout();
        Path config = root.resolve(ConfigSources.PROJECT_FILE_NAME);
        if (!Files.exists(config)) {
            Files.writeString(config, CONFIG_TEMPLATE, StandardCharsets.UTF_8);
            System.out.println("javaguard install: created " + config);
        }
    }

    static boolean insideGitRepository(Path root) {
        for (Path dir = root.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".git"))) {
                return true;
            }
        }
        return false;
    }
}
