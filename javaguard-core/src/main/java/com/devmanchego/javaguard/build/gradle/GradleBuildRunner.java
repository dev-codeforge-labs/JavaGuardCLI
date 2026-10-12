package com.devmanchego.javaguard.build.gradle;

import com.devmanchego.javaguard.build.BuildResult;
import com.devmanchego.javaguard.build.BuildRunner;
import com.devmanchego.javaguard.build.maven.MavenOutputParser;
import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.process.ProcessResult;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.project.BuildToolExecutable;
import com.devmanchego.javaguard.project.GradleProjects;
import com.devmanchego.javaguard.project.ReactorModules;
import com.devmanchego.javaguard.testing.JUnitXmlParser;
import com.devmanchego.javaguard.testing.StackTraceTrimmer;
import com.devmanchego.javaguard.testing.TestFindings;
import com.devmanchego.javaguard.testing.TestSelection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Gradle implementation of {@link BuildRunner} (specification &sect;10.3 steps 2-4, &sect;10.4), the counterpart of the Maven
 * one.
 *
 * <p><b>Compile</b>: {@code gradle -q --console=plain --offline --init-script <init> :a:testClasses :b:testClasses}, one task
 * per module that contains a change or depends on one (Gradle builds a module's own upstream projects by itself, but
 * not its dependents, hence the explicit list). <b>Tests</b>: {@code :a:test --tests X --tests Y} per module with
 * {@code --continue}; the init script switches off Gradle's "no tests found for the filter" failure, because the
 * selection is made for the whole change and each module runs only the classes it has. Results come from the JUnit
 * XML reports under {@code build/test-results/test}, never from console text. A test task that is up to date writes no
 * report and needs none: it passed with the same inputs.
 *
 * <p><b>Fallbacks</b>, each at most once: an offline resolution failure retries online (an offline miss and a
 * non-existent artifact are told apart only online); a project or task Gradle cannot find retries on the whole build.
 */
public final class GradleBuildRunner implements BuildRunner {

    private static final Logger LOG = Logger.getLogger(GradleBuildRunner.class.getName());
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss-SSS")
            .withZone(ZoneOffset.UTC);
    private static final Duration FILESYSTEM_TIMESTAMP_SLACK = Duration.ofSeconds(1);

    private final ProcessRunner processRunner;
    private final BuildToolExecutable executable;
    private final ReactorModules reactor;
    private final Path projectRoot;
    private final Optional<Path> logDir;
    private final boolean offlineFirst;
    private final List<String> extraArgs;
    private final Map<String, String> environment;

    public GradleBuildRunner(ProcessRunner processRunner, BuildToolExecutable executable, ReactorModules reactor, Path projectRoot,
                             Optional<Path> logDir, boolean offlineFirst, List<String> extraArgs, Map<String, String> environment) {
        this.processRunner = processRunner;
        this.executable = executable;
        this.reactor = reactor;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.logDir = logDir;
        this.offlineFirst = offlineFirst;
        this.extraArgs = List.copyOf(extraArgs);
        this.environment = Map.copyOf(environment);
    }

    @Override
    public BuildResult testCompile(Collection<Path> moduleRoots, Duration timeout) {
        Invocation run = invoke(modules -> taskList(modules, "testClasses", List.of()), moduleRoots, true, timeout);
        return compileOutcome(run);
    }

    @Override
    public BuildResult runTests(TestSelection selection, Duration timeout) {
        if (selection.isEmpty()) {
            return BuildResult.notRun("No tests selected for this change.");
        }
        List<String> filters = new ArrayList<>();
        if (!selection.wholeModules()) {
            selection.classes().forEach(c -> {
                filters.add("--tests");
                filters.add(c);
            });
        }
        Instant since = Instant.now().minus(FILESYSTEM_TIMESTAMP_SLACK);
        Invocation run = invoke(modules -> taskList(modules, "test", filters), selection.moduleRoots(), false, timeout);

        if (run.timedOut) {
            return failure(run, List.of(infrastructure("The test run timed out after " + timeout.toSeconds()
                    + " s; its processes were killed.")));
        }
        JUnitXmlParser.Report report = collectReports(since);
        if (report.failed().isEmpty() && !run.result.succeeded()) {
            return compileOutcome(run); // No test failed, yet Gradle did: compilation, dependencies or the tool itself.
        }
        List<Finding> findings = report.failed().stream()
                .map(outcome -> TestFindings.of(outcome, projectRoot, testSourceRoots(), selection.relatedClasses(), projectClasses()))
                .toList();
        List<String> notes = new ArrayList<>(run.notes);
        if (report.reportFiles() == 0 && run.result.succeeded()) {
            notes.add("Gradle wrote no new test reports (the test tasks were up to date, or no test matched the selection).");
        }
        return new BuildResult(findings.isEmpty(), true, report.run(), findings, notes, run.log, run.result.elapsed());
    }

    // --- invocation with fallbacks ---------------------------------------------------------------

    private static final class Invocation {
        ProcessResult result;
        MavenOutputParser.Parsed parsed;
        boolean timedOut;
        final List<String> notes = new ArrayList<>();
        Optional<Path> log = Optional.empty();
    }

    /** Builds the task arguments for the modules to run (empty means the whole build). */
    private interface Tasks {
        List<String> forModules(Set<Path> modules);
    }

    private Invocation invoke(Tasks tasks, Collection<Path> moduleRoots, boolean withDependents, Duration timeout) {
        Invocation invocation = new Invocation();
        boolean offline = offlineFirst;
        boolean scoped = !moduleRoots.isEmpty();
        Set<Path> modules = new LinkedHashSet<>();
        if (scoped) {
            modules.addAll(withDependents ? reactor.withDependents(new LinkedHashSet<>(moduleRoots)) : moduleRoots);
        }
        try (GradleInitScript init = GradleInitScript.create()) {
            for (int attempt = 0; attempt < 3; attempt++) {
                List<String> command = command(init, tasks.forModules(scoped ? modules : Set.of()), offline);
                try {
                    invocation.log = logFile();
                    invocation.result = processRunner.run(command, reactor.reactorRoot(), gradleEnvironment(), timeout,
                            ProcessRunner.DEFAULT_MAX_OUTPUT_CHARS, invocation.log);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Could not start Gradle: " + command, e);
                    invocation.result = new ProcessResult(OptionalInt.of(-1), "Could not start Gradle: " + e.getMessage(), false,
                            false, Duration.ZERO);
                }
                invocation.timedOut = invocation.result.timedOut();
                invocation.parsed = GradleOutputParser.parse(invocation.result.output(), projectRoot);
                if (invocation.result.succeeded() || invocation.timedOut) {
                    break;
                }
                if (scoped && invocation.parsed.selectionFailed()) {
                    scoped = false;
                    invocation.notes.add("Gradle could not select the changed projects; ran the whole build instead.");
                    continue;
                }
                if (offline && invocation.parsed.resolutionFailed()) {
                    offline = false;
                    invocation.notes.add("Offline dependency resolution failed; retried online.");
                    continue;
                }
                break;
            }
        }
        return invocation;
    }

    /** {@code <prefix>task [filters]} for every module, or the bare task name for the whole build. */
    private List<String> taskList(Set<Path> modules, String task, List<String> filters) {
        List<String> out = new ArrayList<>();
        if (modules.isEmpty()) {
            out.add(task);
            out.addAll(filters);
            return out;
        }
        for (Path module : modules) {
            out.add(GradleProjects.taskPrefix(reactor.reactorRoot(), module) + task);
            out.addAll(filters);
        }
        return out;
    }

    private List<String> command(GradleInitScript init, List<String> tasks, boolean offline) {
        List<String> command = new ArrayList<>(executable.command());
        command.add("-q");
        command.add("--console=plain");
        command.add("--continue");
        if (offline) {
            command.add("--offline");
        }
        command.add("--init-script");
        command.add(init.path().toString());
        command.addAll(extraArgs);
        command.addAll(tasks);
        return command;
    }

    private Map<String, String> gradleEnvironment() {
        String existing = environment.getOrDefault("GRADLE_OPTS", "");
        String opts = existing.contains("file.encoding") ? existing : (existing + " -Dfile.encoding=UTF-8").strip();
        return Map.of("GRADLE_OPTS", opts);
    }

    private Optional<Path> logFile() {
        return logDir.map(dir -> {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                return null;
            }
            return dir.resolve("build-" + STAMP.format(Instant.now()) + ".log");
        });
    }

    // --- results ---------------------------------------------------------------------------------

    private BuildResult compileOutcome(Invocation run) {
        if (run.result.succeeded()) {
            return new BuildResult(true, true, 0, List.of(), run.notes, run.log, run.result.elapsed());
        }
        if (run.timedOut) {
            return failure(run, List.of(infrastructure("The Gradle build timed out; its processes were killed.")));
        }
        MavenOutputParser.Parsed parsed = run.parsed;
        List<Finding> findings = new ArrayList<>(parsed.compileErrors());
        findings.addAll(parsed.dependencyFindings());
        if (findings.isEmpty()) {
            findings.add(parsed.resolutionFailed() || parsed.transferFailure()
                    ? infrastructure("Gradle could not resolve dependencies (no network, or a repository is unreachable); "
                    + "the build was not checked.")
                    : MavenOutputParser.buildFailure("Gradle", parsed.tail()));
        }
        return failure(run, findings);
    }

    private BuildResult failure(Invocation run, List<Finding> findings) {
        return new BuildResult(false, true, 0, findings, run.notes, run.log, run.result.elapsed());
    }

    private static Finding infrastructure(String message) {
        return new Finding(MavenOutputParser.BUILD_RULE_ID, "Build failed", Category.INFRASTRUCTURE, Severity.INFO, "gradle",
                Optional.empty(), 0, 0, 0, message, "See the build log in .javaguard/logs/.", Optional.empty(), Map.of(),
                Fingerprints.of(MavenOutputParser.BUILD_RULE_ID, "", message));
    }

    private JUnitXmlParser.Report collectReports(Instant since) {
        JUnitXmlParser.Report total = JUnitXmlParser.Report.empty();
        for (ReactorModules.Module module : reactor.all()) {
            total = total.plus(JUnitXmlParser.parseDirectory(
                    module.info().moduleRoot().resolve("build").resolve("test-results").resolve("test"), since));
        }
        return total;
    }

    private List<Path> testSourceRoots() {
        return reactor.all().stream().flatMap(m -> m.info().testSourceRoots().stream()).toList();
    }

    private Predicate<String> projectClasses() {
        List<Path> roots = new ArrayList<>(testSourceRoots());
        reactor.all().forEach(m -> roots.addAll(m.info().mainSourceRoots()));
        return StackTraceTrimmer.projectClasses(roots);
    }
}
