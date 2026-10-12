package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.cli.ExitCode;
import com.devmanchego.javaguard.cli.Main;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Session state management (specification &sect;4, &sect;19): the ledger and
 * baselines (Phase 4) and, from Phase 12, loop history live in
 * {@code .javaguard/state/<session>/}; clearing removes that directory.
 */
@Command(mixinStandardHelpOptions = true, name = "state", description = "Manage JavaGuard session state.",
        subcommands = StateCommand.ClearCommand.class)
public final class StateCommand implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return ExitCode.SUCCESS.code();
    }

    @Command(mixinStandardHelpOptions = true, name = "clear", description = "Clear loop/session state.")
    static final class ClearCommand implements Callable<Integer> {

        @ParentCommand
        private StateCommand parent;

        @Option(names = "--session", description = "Session id to clear.")
        private String session;

        @Option(names = "--all", description = "Clear state for every session.")
        private boolean all;

        @Override
        public Integer call() {
            if ((session == null) == !all) {
                System.err.println("javaguard state clear: give exactly one of --session <id> or --all");
                return ExitCode.USAGE_ERROR.code();
            }
            JavaGuardPaths paths = new JavaGuardPaths(ProjectRoots.resolve(parent == null ? null : parent.main));
            try {
                List<Path> targets = all ? sessionDirs(paths) : List.of(paths.sessionDir(session));
                for (Path dir : targets) {
                    deleteRecursively(dir);
                }
                System.out.println("javaguard state clear: " + targets.size() + " session(s) cleared in "
                        + paths.sessionsDir());
                return ExitCode.SUCCESS.code();
            } catch (IOException e) {
                System.err.println("javaguard state clear: " + e.getMessage());
                return ExitCode.ENVIRONMENT_ERROR.code();
            }
        }

        private static List<Path> sessionDirs(JavaGuardPaths paths) throws IOException {
            if (!Files.isDirectory(paths.sessionsDir())) {
                return List.of();
            }
            try (Stream<Path> dirs = Files.list(paths.sessionsDir())) {
                return dirs.filter(Files::isDirectory).toList();
            }
        }

        private static void deleteRecursively(Path dir) throws IOException {
            if (!Files.exists(dir)) {
                return;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }
}
