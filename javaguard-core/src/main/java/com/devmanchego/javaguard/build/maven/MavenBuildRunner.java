package com.devmanchego.javaguard.build.maven;

import com.devmanchego.javaguard.build.BuildResult;
import com.devmanchego.javaguard.build.BuildRunner;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.process.ProcessResult;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.project.BuildToolExecutable;
import com.devmanchego.javaguard.project.ReactorModules;
import com.devmanchego.javaguard.testing.JUnitXmlParser;
import com.devmanchego.javaguard.testing.StackTraceTrimmer;
import com.devmanchego.javaguard.testing.TestFindings;
import com.devmanchego.javaguard.testing.TestSelection;
import com.devmanchego.javaguard.change.Fingerprints;

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
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Maven implementation of {@link BuildRunner} (specification &sect;10.3 steps 2-4, &sect;10.4).
 *
 * <p><b>Compile</b>: {@code mvn -q -o -B test-compile -pl <modules> -am -amd}, run from the reactor root.
 * {@code -am} is added to the specification's {@code -pl ... -amd}: a module's upstream siblings are
 * usually not installed in the local repository, so without them in the build a change in module B
 * could not even resolve module A. Upstream modules are recompiled incrementally, which is cheap.
 *
 * <p><b>Fallbacks</b>, in order, each at most once: offline resolution failure retries online;
 * {@code -pl} naming a project Maven cannot find retries on the whole reactor.
 *
 * <p><b>Tests</b>: {@code mvn -q -o -B test -pl <modules> -am -Dtest=<classes>
 * -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false}. The specification says
 * {@code test-compile surefire:test}, but that cannot work offline: Maven resolves the {@code surefire}
 * prefix, or the version of a bare {@code groupId:artifactId:test} goal, from plugin metadata an offline
 * local repository usually lacks, while the {@code test} phase takes the plugin and version from the
 * default lifecycle binding. The extra phases (resources, an up-to-date compile) cost a fraction of a
 * second. Results come from the Surefire XML reports written during the run, never from console text.
 */
public final class MavenBuildRunner implements BuildRunner {

    private static final Logger LOG = Logger.getLogger(MavenBuildRunner.class.getName());
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

    /**
     * @param projectRoot findings carry paths relative to this directory
     * @param logDir      where {@code build-<timestamp>.log} is written, when present
     * @param environment the process environment, for {@code MAVEN_OPTS}
     */
    public MavenBuildRunner(ProcessRunner processRunner, BuildToolExecutable executable, ReactorModules reactor,
                            Path projectRoot, Optional<Path> logDir, boolean offlineFirst, List<String> extraArgs,
                            Map<String, String> environment) {
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
        List<String> goals = List.of("test-compile");
        Invocation run = invoke(goals, selectorsFor(moduleRoots), true, List.of(), timeout);
        return compileOutcome(run);
    }

    /** Class-filtered selections only: a whole-module run with {@code -am} would also run the upstream modules' tests. */
    @Override
    public boolean canCombine(TestSelection selection) {
        return !selection.isEmpty() && !selection.wholeModules();
    }

    /**
     * {@code mvn -q -o -B test -pl <changed and tested modules> -am -amd -Dtest=<classes> ...}: the {@code test} phase compiles
     * main and test sources of every module it builds, so one Maven start covers what {@link #testCompile} and
     * {@link #runTests} do in two (about 1.5 s saved per turn). A failure with no failed test is a compile or build failure.
     */
    @Override
    public CompileAndTest compileAndTest(Collection<Path> moduleRoots, TestSelection selection, Duration timeout) {
        Set<Path> modules = new LinkedHashSet<>(moduleRoots);
        modules.addAll(selection.moduleRoots());
        List<String> properties = List.of("-Dsurefire.failIfNoSpecifiedTests=false", "-DfailIfNoTests=false",
                "-Dtest=" + String.join(",", selection.classes()));
        Instant since = Instant.now().minus(FILESYSTEM_TIMESTAMP_SLACK);
        Invocation run = invoke(List.of("test"), selectorsFor(modules), true, properties, timeout);
        if (run.timedOut) {
            return new CompileAndTest(failure(run, List.of(infrastructure("The build timed out after " + timeout.toSeconds()
                    + " s; its processes were killed."))), Optional.empty());
        }
        JUnitXmlParser.Report report = collectReports(since);
        if (report.failed().isEmpty() && !run.result.succeeded()) {
            return new CompileAndTest(compileOutcome(run), Optional.empty());
        }
        BuildResult compiled = new BuildResult(true, true, 0, List.of(), run.notes, run.log, run.result.elapsed());
        return new CompileAndTest(compiled, Optional.of(testOutcome(run, report, selection)));
    }

    @Override
    public BuildResult runTests(TestSelection selection, Duration timeout) {
        if (selection.isEmpty()) {
            return BuildResult.notRun("No tests selected for this change.");
        }
        List<String> properties = new ArrayList<>(List.of("-Dsurefire.failIfNoSpecifiedTests=false", "-DfailIfNoTests=false"));
        if (!selection.wholeModules()) {
            properties.add("-Dtest=" + String.join(",", selection.classes()));
        }
        Instant since = Instant.now().minus(FILESYSTEM_TIMESTAMP_SLACK);
        Invocation run = invoke(List.of("test"), selectorsFor(selection.moduleRoots()), false,
                properties, timeout);

        if (run.timedOut) {
            return failure(run, List.of(infrastructure("The test run timed out after " + timeout.toSeconds()
                    + " s; its processes were killed.")));
        }
        JUnitXmlParser.Report report = collectReports(since);
        if (report.failed().isEmpty() && !run.result.succeeded()) {
            // No test failed, yet Maven did: compilation, dependencies or the tool itself.
            return compileOutcome(run);
        }
        return testOutcome(run, report, selection);
    }

    private BuildResult testOutcome(Invocation run, JUnitXmlParser.Report report, TestSelection selection) {
        List<Finding> findings = report.failed().stream()
                .map(outcome -> TestFindings.of(outcome, projectRoot, testSourceRoots(), selection.relatedClasses(),
                        projectClasses()))
                .toList();
        List<String> notes = new ArrayList<>(run.notes);
        if (report.reportFiles() == 0 && run.result.succeeded()) {
            notes.add("Maven finished but wrote no test reports; no test matched the selection.");
        }
        return new BuildResult(findings.isEmpty(), true, report.run(), findings, notes, run.log, run.result.elapsed());
    }

    // --- invocation with fallbacks ---------------------------------------------------------------

    /** One logical Maven call: the final attempt's result plus what the fallbacks did. */
    private static final class Invocation {
        ProcessResult result;
        MavenOutputParser.Parsed parsed;
        boolean timedOut;
        final List<String> notes = new ArrayList<>();
        Optional<Path> log = Optional.empty();
    }

    private Invocation invoke(List<String> goals, List<String> selectors, boolean dependents, List<String> properties,
                              Duration timeout) {
        Invocation invocation = new Invocation();
        boolean offline = offlineFirst;
        boolean scoped = !selectors.isEmpty();
        for (int attempt = 0; attempt < 3; attempt++) {
            List<String> command = command(goals, scoped ? selectors : List.of(), dependents, properties, offline);
            try {
                invocation.log = logFile();
                invocation.result = processRunner.run(command, reactor.reactorRoot(), mavenEnvironment(), timeout,
                        ProcessRunner.DEFAULT_MAX_OUTPUT_CHARS, invocation.log);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not start Maven: " + command, e);
                invocation.result = new ProcessResult(java.util.OptionalInt.of(-1), "Could not start Maven: " + e.getMessage(),
                        false, false, Duration.ZERO);
            }
            invocation.timedOut = invocation.result.timedOut();
            invocation.parsed = MavenOutputParser.parse(invocation.result.output(), projectRoot);
            if (invocation.result.succeeded() || invocation.timedOut) {
                break;
            }
            if (scoped && invocation.parsed.selectionFailed()) {
                scoped = false;
                invocation.notes.add("Maven could not select the changed modules with -pl; ran the whole reactor instead.");
                continue;
            }
            if (offline && invocation.parsed.resolutionFailed()) {
                // Maven 4 words an offline miss exactly like an online "no such artifact", so no offline run
                // is ever believed about a missing dependency: confirm online before JG-DEP-003 is reported.
                offline = false;
                invocation.notes.add("Offline dependency resolution failed; retried online.");
                continue;
            }
            break;
        }
        return invocation;
    }

    private List<String> command(List<String> goals, List<String> selectors, boolean dependents, List<String> properties,
                                 boolean offline) {
        List<String> command = new ArrayList<>(executable.command());
        command.add("-q");
        command.add("-B");
        if (offline) {
            command.add("-o");
        }
        command.addAll(goals);
        if (!selectors.isEmpty()) {
            command.add("-pl");
            command.add(String.join(",", selectors));
            command.add("-am");
            if (dependents) {
                command.add("-amd");
            }
        }
        command.addAll(properties);
        command.addAll(extraArgs);
        return command;
    }

    /** Selectors for {@code -pl}; empty (whole reactor) when no module is named. */
    private List<String> selectorsFor(Collection<Path> moduleRoots) {
        return moduleRoots.isEmpty() ? List.of() : reactor.selectors(new LinkedHashSet<>(moduleRoots));
    }

    private Map<String, String> mavenEnvironment() {
        String existing = environment.getOrDefault("MAVEN_OPTS", "");
        String opts = existing.contains("file.encoding") ? existing : (existing + " -Dfile.encoding=UTF-8").strip();
        return Map.of("MAVEN_OPTS", opts);
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
            return failure(run, List.of(infrastructure("The Maven build timed out; its processes were killed.")));
        }
        MavenOutputParser.Parsed parsed = run.parsed;
        List<Finding> findings = new ArrayList<>(parsed.compileErrors());
        findings.addAll(parsed.dependencyFindings());
        if (findings.isEmpty()) {
            findings.add(parsed.resolutionFailed() || parsed.transferFailure()
                    ? infrastructure("Maven could not resolve dependencies (no network, or a repository is unreachable); "
                    + "the build was not checked.")
                    : MavenOutputParser.buildFailure("Maven", parsed.tail()));
        }
        return failure(run, findings);
    }

    private BuildResult failure(Invocation run, List<Finding> findings) {
        return new BuildResult(false, true, 0, findings, run.notes, run.log, run.result.elapsed());
    }

    private static Finding infrastructure(String message) {
        return new Finding(MavenOutputParser.BUILD_RULE_ID, "Build failed", Category.INFRASTRUCTURE, Severity.INFO,
                "maven", Optional.empty(), 0, 0, 0, message, "See the build log in .javaguard/logs/.", Optional.empty(),
                Map.of(), Fingerprints.of(MavenOutputParser.BUILD_RULE_ID, "", message));
    }

    private JUnitXmlParser.Report collectReports(Instant since) {
        JUnitXmlParser.Report total = JUnitXmlParser.Report.empty();
        for (ReactorModules.Module module : reactor.all()) {
            total = total.plus(JUnitXmlParser.parseDirectory(
                    module.info().moduleRoot().resolve("target").resolve("surefire-reports"), since));
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
