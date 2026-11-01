package com.devmanchego.javaguard.cli.command;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The class-data archive ({@code app.jsa}) that halves the start-up of a hook. A dynamic archive records the absolute
 * path of the jar it was dumped from, so it cannot be built in advance and shipped: it is built here, on the machine and in
 * the folder where the distribution lives ({@code javaguard install}, {@code javaguard optimize}), by running the training
 * workload under the same JVM flags as the running process.
 *
 * <p>The launchers always pass {@code -XX:SharedArchiveFile=<folder>/app.jsa} with {@code -Xlog:disable}; a missing,
 * stale or corrupt archive is then ignored by the JVM without printing anything, so everything here is best effort.
 */
final class StartupArchive {

    static final String FILE_NAME = "app.jsa";
    private static final long TRAINING_TIMEOUT_SECONDS = 240;
    private static final long CHECK_TIMEOUT_SECONDS = 60;
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    /** Where the archive lives and what builds it. */
    record Target(Path jar, Path archive, Path java) {
    }

    enum State { ABSENT, USABLE, UNUSABLE }

    record Result(boolean built, String message) {
    }

    private StartupArchive() {
    }

    /** Present only when running from the packaged self-contained jar ({@code javaguard-<version>-all.jar}). */
    static Optional<Target> target() {
        Optional<Path> jar = SelfLauncher.selfContainedJar();
        if (jar.isEmpty() || !jar.get().getFileName().toString().matches("javaguard-.*-all\\.jar")) {
            return Optional.empty();
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java");
        return Optional.of(new Target(jar.get(), jar.get().resolveSibling(FILE_NAME), java));
    }

    /** The flags of the running JVM minus the ones that choose or create an archive. */
    static List<String> jvmFlags(List<String> inputArguments) {
        List<String> flags = new ArrayList<>();
        for (String argument : inputArguments) {
            if (argument.startsWith("-XX:SharedArchiveFile") || argument.startsWith("-XX:ArchiveClassesAtExit")
                    || argument.contains("AutoCreateSharedArchive") || argument.startsWith("-Xshare")) {
                continue;
            }
            flags.add(argument);
        }
        return flags;
    }

    static State state(Target target) {
        if (!Files.isRegularFile(target.archive())) {
            return State.ABSENT;
        }
        return accepts(target, target.archive()) ? State.USABLE : State.UNUSABLE;
    }

    /** Dumps a new archive next to the jar, checks that a JVM accepts it, and swaps it in. */
    static Result build(Target target) {
        Path folder = target.archive().getParent();
        if (!Files.isWritable(folder)) {
            return new Result(false, "the folder " + folder + " is not writable");
        }
        removeInterruptedBuilds(folder);
        Path temporary = folder.resolve(FILE_NAME + ".tmp-" + ProcessHandle.current().pid());
        Path work = null;
        try {
            work = Files.createTempDirectory("javaguard-training");
            List<String> command = baseCommand(target);
            command.add("-XX:ArchiveClassesAtExit=" + temporary);
            command.addAll(List.of("-cp", target.jar().toString(), "com.devmanchego.javaguard.cli.Main", "cds-train", work.toString()));
            if (run(command, TRAINING_TIMEOUT_SECONDS) != 0 || !Files.isRegularFile(temporary) || Files.size(temporary) == 0) {
                return new Result(false, "the training run did not produce an archive");
            }
            if (!accepts(target, temporary)) {
                return new Result(false, "the JVM did not accept the archive it produced");
            }
            deleteReadOnly(target.archive());
            try {
                Files.move(temporary, target.archive(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target.archive(), StandardCopyOption.REPLACE_EXISTING);
            }
            return new Result(true, target.archive() + " (" + Files.size(target.archive()) / 1_048_576 + " MB)");
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Result(false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            deleteReadOnly(temporary);
            deleteTree(work);
        }
    }

    private static boolean accepts(Target target, Path archive) {
        try {
            List<String> command = baseCommand(target);
            command.addAll(List.of("-Xshare:on", "-XX:SharedArchiveFile=" + archive, "-cp", target.jar().toString(),
                    "com.devmanchego.javaguard.cli.Main", "--version"));
            return run(command, CHECK_TIMEOUT_SECONDS) == 0;
        } catch (IOException | RuntimeException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static List<String> baseCommand(Target target) {
        List<String> command = new ArrayList<>();
        command.add(target.java().toString());
        command.addAll(jvmFlags(ManagementFactory.getRuntimeMXBean().getInputArguments()));
        return command;
    }

    private static int run(List<String> command, long timeoutSeconds) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        process.getOutputStream().close();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return -1;
        }
        return process.exitValue();
    }

    /** Temporary archives left by a build that was killed half way. */
    private static void removeInterruptedBuilds(Path folder) {
        try (Stream<Path> leftovers = Files.list(folder)) {
            leftovers.filter(path -> path.getFileName().toString().startsWith(FILE_NAME + ".tmp-")).forEach(StartupArchive::deleteReadOnly);
        } catch (IOException | RuntimeException e) {
            // Best effort.
        }
    }

    /** The JVM writes the archive read-only, and on Windows {@link Files#delete} will not remove such a file. */
    private static void deleteReadOnly(Path file) {
        try {
            file.toFile().setWritable(true);
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException e) {
            // A temporary file left behind is harmless; for the archive, the move that follows reports the failure.
        }
    }

    private static void deleteTree(Path root) {
        if (root == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // Temporary files only.
                }
            });
        } catch (IOException e) {
            // Temporary files only.
        }
    }
}
