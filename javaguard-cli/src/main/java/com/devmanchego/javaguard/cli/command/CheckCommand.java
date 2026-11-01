package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.change.ChangeTracker;
import com.devmanchego.javaguard.change.GitClient;
import com.devmanchego.javaguard.change.ProcessGitClient;
import com.devmanchego.javaguard.cli.ExitCode;
import com.devmanchego.javaguard.cli.Main;
import com.devmanchego.javaguard.config.ConfigException;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.config.LoadedConfig;
import com.devmanchego.javaguard.gate.EndOfTurnGate;
import com.devmanchego.javaguard.gate.FastGatePipeline;
import com.devmanchego.javaguard.gate.FastGatePipeline.FileUnderCheck;
import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.model.ChangeType;
import com.devmanchego.javaguard.model.FileChange;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.HookEvent;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.process.ProcessResult;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.report.AgentReportRenderer;
import com.devmanchego.javaguard.report.HumanReportRenderer;
import com.devmanchego.javaguard.report.JsonReportWriter;
import com.devmanchego.javaguard.report.SarifReportWriter;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Manual run for humans and CI (specification &sect;4): the fast pipeline of the POST_EDIT gate over
 * the given files, or over the files changed against {@code HEAD}; with {@code --full}, the end-of-turn pipeline
 * (compile, affected tests, deep analysis) over the same files.
 *
 * <p>Scoping: files named explicitly are analyzed in full (everything found is reported); files
 * taken from {@code git} are compared with their {@code HEAD} content, so only what the change
 * introduced is reported, exactly as the hook would.
 */
@Command(mixinStandardHelpOptions = true, name = "check", description = "Run JavaGuard checks manually against files or the current changes.")
public final class CheckCommand implements Callable<Integer> {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(10);

    @ParentCommand
    private Main main;

    @Parameters(description = "Files (or directories) to check in full. Default: Java files changed vs. HEAD.")
    private List<Path> paths;

    @Option(names = "--full", description = "Run the end-of-turn pipeline (compile + affected tests + deep analysis).")
    private boolean full;

    @Option(names = "--format", defaultValue = "text", description = "Valid values: text, md, json, sarif.")
    private String format;

    @Option(names = "--output", description = "Write the report to this file instead of stdout.")
    private Path output;

    @Override
    public Integer call() {
        if (!List.of("text", "md", "json", "sarif").contains(format.toLowerCase())) {
            System.err.println("javaguard check: unknown --format '" + format + "' (valid: text, md, json, sarif)");
            return ExitCode.USAGE_ERROR.code();
        }
        Path root = ProjectRoots.resolve(main);
        LoadedConfig loaded;
        try {
            loaded = ManualConfig.load(main, root);
        } catch (ConfigException e) {
            System.err.println("javaguard check: " + e.getMessage());
            return ExitCode.CONFIG_ERROR.code();
        }
        JavaGuardPaths state = new JavaGuardPaths(root);
        state.ensureLayout();

        List<String> notes = new ArrayList<>(loaded.warnings());
        if (full) {
            return runFull(root, state, loaded.config(), notes);
        }
        List<FileUnderCheck> files;
        try {
            files = paths == null || paths.isEmpty() ? changedAgainstHead(root, notes) : explicitFiles(root, notes);
        } catch (IOException e) {
            System.err.println("javaguard check: " + e.getMessage());
            return ExitCode.ENVIRONMENT_ERROR.code();
        }

        GateResult result = new FastGatePipeline(state, loaded.config(), Clock.systemUTC())
                .run(GateKind.POST_EDIT, files, notes);
        return emit(result, loaded.config());
    }

    /**
     * {@code --full}: the end-of-turn pipeline (compile, affected tests, deep analysis, file-local checks) over the given files,
     * or the files changed against {@code HEAD}. The files are recorded in a throw-away session as if an agent had just written
     * them - baselines from {@code HEAD} where git has them, so only what the change introduced is reported - and the end-of-turn
     * gate runs on that session, which is deleted afterwards.
     */
    private int runFull(Path root, JavaGuardPaths state, JavaGuardConfig config, List<String> notes) {
        List<Path> files;
        try {
            files = paths == null || paths.isEmpty() ? changedFilesAgainstHead(root) : explicitFiles(root, notes).stream()
                    .map(FileUnderCheck::absolute).toList();
        } catch (IOException e) {
            System.err.println("javaguard check: " + e.getMessage());
            return ExitCode.ENVIRONMENT_ERROR.code();
        }
        String session = "check-full-" + System.currentTimeMillis();
        Clock clock = Clock.systemUTC();
        GitClient git = new ProcessGitClient(new ProcessRunner(), GIT_TIMEOUT);
        ChangeTracker tracker = new ChangeTracker(state, git, clock);
        try {
            if (!files.isEmpty()) {
                List<FileChange> changes = files.stream().map(f -> FileChange.rewrite(f, ChangeType.UPDATE)).toList();
                tracker.recordAfterEdit(new HookEvent(AgentKind.MANUAL, GateKind.POST_EDIT, session, root, Optional.empty(), "check",
                        changes, false, Optional.empty(), clock.instant()), tracker.gateRun(GateKind.END_OF_TURN));
            } else {
                notes.add("No changed files against HEAD.");
            }
            GateResult result = EndOfTurnGate.standard(state, config, ProcessEnvironment.get(), tracker, git, GIT_TIMEOUT, clock)
                    .run(new HookEvent(AgentKind.MANUAL, GateKind.END_OF_TURN, session, root, Optional.empty(), "", List.of(), false,
                            Optional.empty(), clock.instant()));
            List<String> allNotes = new ArrayList<>(notes);
            allNotes.addAll(result.notes());
            return emit(new GateResult(result.gate(), result.verdict() == Verdict.HALT ? Verdict.BLOCK : result.verdict(), result.blocking(),
                    result.advisory(), allNotes, Optional.empty(), result.fullReport(), result.newSuppressions(), result.elapsed()), config);
        } catch (IOException | RuntimeException e) {
            System.err.println("javaguard check: " + e.getMessage());
            return ExitCode.INTERNAL_ERROR.code();
        } finally {
            deleteQuietly(state.sessionDir(session));
        }
    }

    private static List<Path> changedFilesAgainstHead(Path root) throws IOException {
        ProcessRunner runner = new ProcessRunner();
        ProcessResult tracked = git(runner, root, "diff", "--name-only", "--relative", "HEAD");
        if (!tracked.succeeded()) {
            throw new IOException("cannot list the changes against HEAD (not a git repository, or no commits yet); "
                    + "pass the files to check explicitly");
        }
        Set<String> relatives = new LinkedHashSet<>();
        tracked.output().lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(relatives::add);
        ProcessResult untracked = git(runner, root, "ls-files", "--others", "--exclude-standard");
        if (untracked.succeeded()) {
            untracked.output().lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(relatives::add);
        }
        return relatives.stream().map(r -> root.resolve(r).normalize()).filter(Files::isRegularFile).toList();
    }

    private static void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            // A leftover session directory is harmless; 'javaguard state clear' removes it.
        }
    }

    private int emit(GateResult result, JavaGuardConfig config) {
        String report = render(result, config);
        try {
            if (output != null) {
                Path parent = output.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(output, report, StandardCharsets.UTF_8);
            } else {
                System.out.print(report);
            }
        } catch (IOException e) {
            System.err.println("javaguard check: cannot write " + output + ": " + e.getMessage());
            return ExitCode.ENVIRONMENT_ERROR.code();
        }
        return result.verdict() == Verdict.BLOCK ? ExitCode.BLOCK.code() : ExitCode.SUCCESS.code();
    }

    private String render(GateResult result, JavaGuardConfig config) {
        return switch (format.toLowerCase()) {
            case "md" -> HumanReportRenderer.render(result, Clock.systemUTC().instant());
            case "json" -> JsonReportWriter.write(result) + "\n";
            case "sarif" -> SarifReportWriter.write(
                    Stream.concat(result.blocking().stream(), result.advisory().stream()).toList()) + "\n";
            default -> text(result, config);
        };
    }

    private static String text(GateResult result, JavaGuardConfig config) {
        StringBuilder out = new StringBuilder();
        if (result.verdict() == Verdict.PASS) {
            out.append("JAVAGUARD PASS - 0 blocking, 0 advisory\n");
        } else {
            out.append(AgentReportRenderer.render(result, config.report()).report()).append('\n');
        }
        if (!result.notes().isEmpty()) {
            out.append("\nNotes:\n");
            result.notes().forEach(n -> out.append("- ").append(n).append('\n'));
        }
        return out.toString();
    }

    // --- file selection -----------------------------------------------------------------------

    private List<FileUnderCheck> explicitFiles(Path root, List<String> notes) throws IOException {
        Set<Path> javaFiles = new LinkedHashSet<>();
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path given : paths) {
            Path absolute = cwd.resolve(given).normalize();
            if (Files.isDirectory(absolute)) {
                try (Stream<Path> walk = Files.walk(absolute)) {
                    walk.filter(p -> checked(p.getFileName().toString()) && Files.isRegularFile(p)).forEach(javaFiles::add);
                }
            } else if (Files.isRegularFile(absolute)) {
                javaFiles.add(absolute);
            } else {
                notes.add("Not found: " + given);
            }
        }
        List<FileUnderCheck> files = new ArrayList<>();
        for (Path file : javaFiles) {
            if (!file.startsWith(root)) {
                notes.add("Outside the project root, skipped: " + file);
                continue;
            }
            files.add(new FileUnderCheck(file, root.relativize(file).toString().replace('\\', '/'), ""));
        }
        return files;
    }

    private List<FileUnderCheck> changedAgainstHead(Path root, List<String> notes) throws IOException {
        ProcessRunner runner = new ProcessRunner();
        ProcessResult tracked = git(runner, root, "diff", "--name-only", "--relative", "HEAD");
        if (!tracked.succeeded()) {
            throw new IOException("cannot list the changes against HEAD (not a git repository, or no commits yet); "
                    + "pass the files to check explicitly");
        }
        Set<String> relatives = new LinkedHashSet<>();
        tracked.output().lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(relatives::add);
        ProcessResult untracked = git(runner, root, "ls-files", "--others", "--exclude-standard");
        if (untracked.succeeded()) {
            untracked.output().lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(relatives::add);
        }
        GitClient git = new ProcessGitClient(runner, GIT_TIMEOUT);
        List<FileUnderCheck> files = new ArrayList<>();
        for (String relative : relatives) {
            if (!checked(relative)) {
                continue;
            }
            Path absolute = root.resolve(relative).normalize();
            String baseline = git.showHead(absolute).map(b -> new String(b, StandardCharsets.UTF_8)).orElse("");
            files.add(new FileUnderCheck(absolute, relative, baseline));
        }
        if (files.isEmpty()) {
            notes.add("No changed Java, migration or configuration files against HEAD.");
        }
        return files;
    }

    /** Java sources, database migrations and Spring Boot configuration files: what the fast gate checks. */
    private static boolean checked(String relative) {
        return relative.endsWith(".java") || com.devmanchego.javaguard.analysis.db.MigrationChecks.isMigration(relative)
                || com.devmanchego.javaguard.analysis.config.ConfigFiles.isConfigFile(relative);
    }

    private static ProcessResult git(ProcessRunner runner, Path root, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString(), "-c", "core.quotepath=off"));
        command.addAll(List.of(args));
        return runner.run(command, root, Map.of(), GIT_TIMEOUT);
    }
}
