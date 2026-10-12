package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.cli.ExitCode;
import com.devmanchego.javaguard.cli.Main;
import com.devmanchego.javaguard.install.AgentHookConfig;
import com.devmanchego.javaguard.install.ClaudeHookConfig;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.UnaryOperator;

/**
 * Removes only JavaGuard's own entries from an agent's hook configuration
 * (specification &sect;4), for Claude Code, Gemini CLI and Codex. The {@code .javaguard/} state directory and {@code javaguard.yml}
 * are left alone: they hold the user's history and configuration.
 */
@Command(mixinStandardHelpOptions = true, name = "uninstall", description = "Remove JavaGuard hooks from an AI coding CLI's configuration.")
public final class UninstallCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Option(names = "--agent", required = true, description = "Valid values: claude, gemini, codex, all.")
    private String agent;

    @Option(names = "--scope", defaultValue = "project", description = "Valid values: project, user.")
    private String scope;

    @Override
    public Integer call() {
        if (!List.of("claude", "gemini", "codex", "all").contains(agent.toLowerCase())) {
            System.err.println("javaguard uninstall: unknown --agent '" + agent + "' (valid: claude, gemini, codex, all)");
            return ExitCode.USAGE_ERROR.code();
        }
        if (!List.of("project", "user").contains(scope.toLowerCase())) {
            System.err.println("javaguard uninstall: unknown --scope '" + scope + "' (valid: project, user)");
            return ExitCode.USAGE_ERROR.code();
        }
        String which = agent.toLowerCase();
        Path root = ProjectRoots.resolve(main);
        Path base = scope.equalsIgnoreCase("user") ? Path.of(System.getProperty("user.home")) : root;
        int result = ExitCode.SUCCESS.code();
        for (String name : which.equals("all") ? List.of("claude", "gemini", "codex") : List.of(which)) {
            int exit = switch (name) {
                case "claude" -> uninstall(scope.equalsIgnoreCase("user") ? ClaudeSettingsFile.userScope(base)
                        : ClaudeSettingsFile.projectScope(root), "Claude Code", ClaudeHookConfig::uninstall);
                case "gemini" -> uninstall(new ClaudeSettingsFile(base.resolve(".gemini").resolve("settings.json")), "Gemini CLI",
                        settings -> AgentHookConfig.uninstall(settings, AgentHookConfig.Agent.GEMINI));
                default -> uninstall(new ClaudeSettingsFile(base.resolve(".codex").resolve("hooks.json")), "Codex",
                        settings -> AgentHookConfig.uninstall(settings, AgentHookConfig.Agent.CODEX));
            };
            if (result == ExitCode.SUCCESS.code()) {
                result = exit;
            }
        }
        return result;
    }

    private static int uninstall(ClaudeSettingsFile settingsFile, String label, UnaryOperator<ObjectNode> remover) {
        if (!settingsFile.exists()) {
            System.out.println("javaguard uninstall: " + settingsFile.path() + " does not exist; nothing to remove.");
            return ExitCode.SUCCESS.code();
        }
        try {
            ObjectNode current = settingsFile.read();
            ObjectNode updated = remover.apply(current);
            if (updated.equals(current)) {
                System.out.println("javaguard uninstall: no JavaGuard hooks in " + settingsFile.path() + ".");
            } else {
                Optional<Path> backup = settingsFile.write(updated, Instant.now());
                System.out.println("javaguard uninstall: removed JavaGuard " + label + " hooks from " + settingsFile.path());
                backup.ifPresent(b -> System.out.println("  previous file saved as " + b));
            }
        } catch (ClaudeSettingsFile.UnreadableException e) {
            System.err.println("javaguard uninstall: " + e.getMessage());
            return ExitCode.CONFIG_ERROR.code();
        } catch (IOException e) {
            System.err.println("javaguard uninstall: " + e.getMessage());
            return ExitCode.ENVIRONMENT_ERROR.code();
        }
        return ExitCode.SUCCESS.code();
    }
}
