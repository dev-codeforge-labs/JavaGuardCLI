package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.analysis.spotbugs.SpotBugsRunner;
import com.devmanchego.javaguard.build.ClasspathBuildFiles;
import com.devmanchego.javaguard.build.ClasspathCache;
import com.devmanchego.javaguard.build.ClasspathCacheStore;
import com.devmanchego.javaguard.build.maven.MavenClasspathExtractor;
import com.devmanchego.javaguard.cli.ExitCode;
import com.devmanchego.javaguard.cli.Main;
import com.devmanchego.javaguard.config.ConfigException;
import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.config.LoadedConfig;
import com.devmanchego.javaguard.consult.ConsultSupport;
import com.devmanchego.javaguard.install.AgentHookConfig;
import com.devmanchego.javaguard.install.ClaudeHookConfig;
import com.devmanchego.javaguard.process.ProcessResult;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.project.BuildTool;
import com.devmanchego.javaguard.project.BuildToolExecutable;
import com.devmanchego.javaguard.project.BuildToolLocator;
import com.devmanchego.javaguard.project.ModuleInfo;
import com.devmanchego.javaguard.project.ProjectDetector;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.tools.ToolProvider;

/**
 * Environment diagnostics (specification &sect;4): runtime, project and build tool, classpath cache,
 * git, configuration and the Claude Code hook configuration. Each capability the plan adds later
 * (consultation CLIs, notifications, Codex feature flag, Gemini/Codex hooks, Gradle) adds its rows
 * in its own phase.
 *
 * <p>Exit code: 0 when nothing failed (warnings are advice), 3 for an invalid configuration,
 * 4 for an environment problem (specification &sect;17).
 */
@Command(mixinStandardHelpOptions = true, name = "doctor", description = "Diagnose the JavaGuard setup for this project and the installed agents.")
public final class DoctorCommand implements Callable<Integer> {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(5);

    enum Status { OK, WARN, FAIL }

    record Row(Status status, String name, String detail, String remedy) {
    }

    @ParentCommand
    private Main main;

    @Override
    public Integer call() {
        return run(ProjectRoots.resolve(main), main == null ? null : main.configFile,
                Path.of(System.getProperty("user.home")), ProcessEnvironment.get(), System.out);
    }

    int run(Path root, Path explicitConfig, Path userHome, Map<String, String> env, PrintStream out) {
        List<Row> rows = new ArrayList<>();
        JavaGuardPaths paths = new JavaGuardPaths(root);

        rows.add(runtime());
        Optional<LoadedConfig> config = configuration(root, explicitConfig, userHome, env, rows);
        JavaGuardConfig effective = config.map(LoadedConfig::config).orElse(null);
        Optional<ModuleInfo> module = project(root, effective, rows);
        module.ifPresent(m -> buildTool(m, effective, env, rows));
        module.ifPresent(m -> classpathCache(m, paths, rows));
        rows.add(git(root));
        startupArchive().ifPresent(rows::add);
        rows.add(claudeHooks(root, userHome));
        otherAgentHooks(AgentHookConfig.Agent.GEMINI, root, userHome, rows);
        otherAgentHooks(AgentHookConfig.Agent.CODEX, root, userHome, rows);
        consultation(effective, env, rows);
        analyzers(effective, rows);
        rows.add(disabled(paths, env));

        render(rows, out);
        boolean configInvalid = config.isEmpty();
        boolean anyFail = rows.stream().anyMatch(r -> r.status() == Status.FAIL);
        if (configInvalid) {
            return ExitCode.CONFIG_ERROR.code();
        }
        return anyFail ? ExitCode.ENVIRONMENT_ERROR.code() : ExitCode.SUCCESS.code();
    }

    // --- rows ---------------------------------------------------------------------------------

    private static Row runtime() {
        int feature = Runtime.version().feature();
        if (feature < 21) {
            return new Row(Status.FAIL, "Java runtime", "Java " + feature + " is too old.",
                    "JavaGuard needs Java 21 or newer.");
        }
        if (ToolProvider.getSystemJavaCompiler() == null) {
            return new Row(Status.FAIL, "Java runtime", "Java " + feature + " without the compiler module (a JRE).",
                    "Run JavaGuard with a full JDK: in-process compilation needs jdk.compiler.");
        }
        return new Row(Status.OK, "Java runtime", "Java " + feature + " with the compiler module.", "");
    }

    private static Optional<LoadedConfig> configuration(Path root, Path explicitConfig, Path userHome,
                                                        Map<String, String> env, List<Row> rows) {
        try {
            LoadedConfig loaded = ConfigLoader.load(
                    ConfigSources.standard(root, Optional.ofNullable(explicitConfig),
                            userHome, env));
            if (loaded.warnings().isEmpty()) {
                rows.add(new Row(Status.OK, "Configuration", "Valid.", ""));
            } else {
                rows.add(new Row(Status.WARN, "Configuration", String.join(" ", loaded.warnings()),
                        "Remove or fix the unknown keys in javaguard.yml."));
            }
            return Optional.of(loaded);
        } catch (ConfigException e) {
            rows.add(new Row(Status.FAIL, "Configuration", oneLine(e.getMessage()),
                    "Fix the reported keys in javaguard.yml (or ~/.javaguard/config.yml)."));
            return Optional.empty();
        }
    }

    private static Optional<ModuleInfo> project(Path root, JavaGuardConfig config, List<Row> rows) {
        Optional<BuildTool> configured = config == null ? Optional.empty() : switch (config.build().tool()) {
            case AUTO -> Optional.empty();
            case MAVEN -> Optional.of(BuildTool.MAVEN);
            case GRADLE -> Optional.of(BuildTool.GRADLE);
        };
        Optional<ModuleInfo> module = ProjectDetector.detect(root, configured);
        if (module.isEmpty()) {
            rows.add(new Row(Status.FAIL, "Project", "No pom.xml or build.gradle found at or above " + root + ".",
                    "Run from a Maven or Gradle project, or pass --project-dir."));
            return module;
        }
        ModuleInfo m = module.get();
        rows.add(new Row(Status.OK, "Project", m.buildTool() + " module " + m.moduleRoot() + ", Java release "
                + m.javaRelease() + ", frameworks " + (m.frameworks().isEmpty() ? "none" : m.frameworks()) + ".", ""));
        return module;
    }

    private static void buildTool(ModuleInfo module, JavaGuardConfig config, Map<String, String> env, List<Row> rows) {
        boolean daemon = config != null && config.build().maven().useDaemon();
        Optional<BuildToolExecutable> exe = module.buildTool() == BuildTool.MAVEN
                ? BuildToolLocator.maven(module.reactorRoot(), daemon, env, WINDOWS)
                : BuildToolLocator.gradle(module.reactorRoot(), env, WINDOWS);
        if (exe.isPresent()) {
            rows.add(new Row(Status.OK, "Build tool", exe.get().command().get(0) + " (" + exe.get().source() + ").", ""));
        } else {
            rows.add(new Row(Status.WARN, "Build tool", "No " + module.buildTool() + " executable or wrapper found.",
                    "Install " + module.buildTool() + " or add its wrapper; the end-of-turn gate needs it."));
        }
    }

    private static void classpathCache(ModuleInfo module, JavaGuardPaths paths, List<Row> rows) {
        ClasspathCacheStore store = new ClasspathCacheStore(paths.classpathCacheDir());
        Optional<ClasspathCache> cache = store.get(module.moduleRoot());
        if (cache.isEmpty()) {
            rows.add(new Row(Status.WARN, "Classpath cache", "Not built.",
                    "Run 'javaguard warmup' so edited files are compiled in-process."));
        } else if (store.isStale(cache.get(), ClasspathBuildFiles.of(module))) {
            rows.add(new Row(Status.WARN, "Classpath cache", "Stale: a build file changed since it was built.",
                    "Run 'javaguard warmup'."));
        } else {
            rows.add(new Row(Status.OK, "Classpath cache", cache.get().compileClasspath().size()
                    + " compile entries, release " + cache.get().release() + ".", ""));
        }
    }

    /** Only for the packaged distribution: the archive that halves the start-up of every hook. */
    private static Optional<Row> startupArchive() {
        if (Boolean.getBoolean("javaguard.training")) {
            return Optional.empty();
        }
        return StartupArchive.target().map(target -> switch (StartupArchive.state(target)) {
            case USABLE -> new Row(Status.OK, "Startup archive", target.archive().toString(), "");
            case ABSENT -> new Row(Status.WARN, "Startup archive", "Not built: every hook starts about 0.4 s slower.",
                    "Run 'javaguard optimize'.");
            case UNUSABLE -> new Row(Status.WARN, "Startup archive", "Stale or unusable (the folder was moved or the JVM changed): "
                    + "hooks start about 0.4 s slower.", "Run 'javaguard optimize --force'.");
        });
    }

    private static Row git(Path root) {
        try {
            ProcessResult result = new ProcessRunner().run(List.of("git", "--version"), root, Map.of(), GIT_TIMEOUT);
            if (result.succeeded()) {
                boolean repository = InstallCommand.insideGitRepository(root);
                if (repository) {
                    ProcessResult top = new ProcessRunner().run(List.of("git", "-C", root.toString(), "rev-parse", "--show-toplevel"),
                            root, Map.of(), GIT_TIMEOUT);
                    if (!top.succeeded() && top.output().contains("dubious ownership")) {
                        return new Row(Status.WARN, "Git", "git refuses to read this repository: it belongs to another user "
                                + "('dubious ownership'), so edits made through a shell cannot be detected.",
                                "If you trust it, run: git config --global --add safe.directory " + root.toString().replace('\\', '/'));
                    }
                }
                return new Row(Status.OK, "Git", oneLine(result.output()) + (repository ? "" : "; not a repository."), "");
            }
        } catch (IOException e) {
            // Falls through to the warning below.
        }
        return new Row(Status.WARN, "Git", "git is not available.",
                "Without git, baselines come from edit reconstruction or snapshots only, and 'check' needs explicit files.");
    }

    private static Row claudeHooks(Path root, Path userHome) {
        List<String> found = new ArrayList<>();
        for (ClaudeSettingsFile file : List.of(ClaudeSettingsFile.projectScope(root), ClaudeSettingsFile.userScope(userHome))) {
            if (!file.exists()) {
                continue;
            }
            try {
                List<ClaudeHookConfig.Gate> gates = ClaudeHookConfig.installedGates(file.read());
                if (!gates.isEmpty()) {
                    found.add(file.path() + " (" + gates.stream().map(g -> g.cliName()).collect(Collectors.joining(", "))
                            + ")");
                }
            } catch (ClaudeSettingsFile.UnreadableException | IOException e) {
                return new Row(Status.WARN, "Claude Code hooks", oneLine(e.getMessage()),
                        "Fix the JSON so 'javaguard install --agent claude' can merge into it.");
            }
        }
        if (found.isEmpty()) {
            return new Row(Status.WARN, "Claude Code hooks", "Not installed.",
                    "Run 'javaguard install --agent claude'.");
        }
        return new Row(Status.OK, "Claude Code hooks", String.join("; ", found) + ".", "");
    }

    /**
     * Gemini CLI and Codex are optional: they get a row only when their hooks are installed or their configuration
     * directory exists, so a Claude-only project is not nagged about agents it does not use.
     */
    private static void otherAgentHooks(AgentHookConfig.Agent agent, Path root, Path userHome, List<Row> rows) {
        boolean gemini = agent == AgentHookConfig.Agent.GEMINI;
        String label = gemini ? "Gemini CLI hooks" : "Codex hooks";
        String dir = gemini ? ".gemini" : ".codex";
        String fileName = gemini ? "settings.json" : "hooks.json";
        List<String> found = new ArrayList<>();
        boolean present = false;
        for (Path base : List.of(root, userHome)) {
            present |= Files.isDirectory(base.resolve(dir));
            ClaudeSettingsFile file = new ClaudeSettingsFile(base.resolve(dir).resolve(fileName));
            if (!file.exists()) {
                continue;
            }
            try {
                com.fasterxml.jackson.databind.JsonNode settings = file.read();
                List<AgentHookConfig.Gate> gates = AgentHookConfig.installedGates(settings, agent);
                if (!gates.isEmpty()) {
                    found.add(file.path() + " (" + gates.stream().map(g -> g.cliName()).collect(Collectors.joining(", ")) + ")");
                    if (System.getProperty("os.name", "").toLowerCase().contains("win")
                            && AgentHookConfig.hasWindowsCommandWithoutCallOperator(settings, agent)) {
                        rows.add(new Row(Status.WARN, label, file.path() + " has JavaGuard hooks that fail under PowerShell, which "
                                + (gemini ? "Gemini CLI" : "Codex") + " uses on Windows: the hook command must start with '& '.",
                                "Run 'javaguard install --agent " + agent.cliName() + "' again to rewrite them."));
                    }
                }
            } catch (ClaudeSettingsFile.UnreadableException | IOException e) {
                rows.add(new Row(Status.WARN, label, oneLine(e.getMessage()),
                        "Fix the JSON so 'javaguard install --agent " + agent.cliName() + "' can merge into it."));
                return;
            }
        }
        if (found.isEmpty()) {
            if (present) {
                rows.add(new Row(Status.WARN, label, "Not installed.", "Run 'javaguard install --agent " + agent.cliName() + "'."));
            }
            return;
        }
        rows.add(new Row(Status.OK, label, String.join("; ", found) + ".", ""));
        if (!gemini) {
            codexFeatureFlag(root, userHome).ifPresent(rows::add);
        }
    }

    private static final Pattern CODEX_HOOKS_DISABLED = Pattern.compile("(?m)^\\s*(?:hooks|codex_hooks)\\s*=\\s*false\\b");

    /** Hooks are on by default in current Codex; an explicit {@code false} in {@code config.toml} turns them off. */
    private static Optional<Row> codexFeatureFlag(Path root, Path userHome) {
        for (Path config : List.of(root.resolve(".codex").resolve("config.toml"), userHome.resolve(".codex").resolve("config.toml"))) {
            try {
                if (Files.isRegularFile(config) && CODEX_HOOKS_DISABLED.matcher(Files.readString(config)).find()) {
                    return Optional.of(new Row(Status.WARN, "Codex feature flag", "Hooks are disabled in " + config + ".",
                            "Set 'hooks = true' under [features] (alias: codex_hooks), or JavaGuard never runs in Codex."));
                }
            } catch (IOException e) {
                // An unreadable config is Codex's problem to report; the hooks file itself was fine.
            }
        }
        return Optional.empty();
    }

    /** SpotBugs runs only with FindSecBugs next to it; a distribution that lost the plugin jar silently loses the security patterns. */
    private static void analyzers(JavaGuardConfig config, List<Row> rows) {
        if (config != null && !config.spotbugs().enabled()) {
            rows.add(new Row(Status.OK, "SpotBugs", "Disabled (spotbugs.enabled: false).", ""));
            return;
        }
        Optional<java.io.File> plugin = SpotBugsRunner.findSecBugsJar();
        rows.add(plugin.isPresent()
                ? new Row(Status.OK, "SpotBugs", "FindSecBugs plugin: " + plugin.get().getName() + ".", "")
                : new Row(Status.WARN, "SpotBugs", "The FindSecBugs plugin was not found; only SpotBugs' own detectors run.",
                        "Keep plugins/findsecbugs-plugin.jar next to the javaguard jar or executable."));
    }

    private static void consultation(JavaGuardConfig config, Map<String, String> env, List<Row> rows) {
        if (config == null || !config.consult().enabled()) {
            rows.add(new Row(Status.OK, "Consultation", "Disabled (consult.enabled: false).", ""));
            return;
        }
        ConsultSupport support = new ConsultSupport(config.consult(), env);
        List<String> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        support.availability().forEach((agent, path) -> (path.isPresent() ? found : missing).add(agent.cliName()));
        if (found.isEmpty()) {
            rows.add(new Row(Status.WARN, "Consultation", "Enabled, but no consultant was found on PATH (" + String.join(", ", missing) + ").",
                    "Install one of them or set consult.commands.<agent>; without one a stuck agent halts instead of getting a second opinion."));
        } else {
            rows.add(new Row(Status.OK, "Consultation", "Available: " + String.join(", ", found)
                    + (missing.isEmpty() ? "" : "; not found: " + String.join(", ", missing)) + ".", ""));
        }
    }

    private static Row disabled(JavaGuardPaths paths, Map<String, String> env) {
        if (paths.isDisabled(env)) {
            return new Row(Status.WARN, "Enabled", "JavaGuard is disabled (JAVAGUARD_DISABLED=1 or .javaguard/disabled).",
                    "Unset the variable or delete .javaguard/disabled to re-enable it.");
        }
        return new Row(Status.OK, "Enabled", "Yes.", "");
    }

    // --- output -------------------------------------------------------------------------------

    private static void render(List<Row> rows, PrintStream out) {
        int width = rows.stream().mapToInt(r -> r.name().length()).max().orElse(0);
        for (Row row : rows) {
            out.println(String.format("[%-4s] %-" + width + "s  %s", row.status(), row.name(), row.detail()));
            if (!row.remedy().isEmpty() && row.status() != Status.OK) {
                out.println("       " + " ".repeat(width) + "  -> " + row.remedy());
            }
        }
    }

    private static String oneLine(String text) {
        return text.lines().map(String::strip).filter(l -> !l.isEmpty()).collect(Collectors.joining(" "));
    }
}
