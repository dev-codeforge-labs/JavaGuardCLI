package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.build.ClasspathCache;
import com.devmanchego.javaguard.build.ClasspathCacheStore;
import com.devmanchego.javaguard.build.gradle.GradleClasspathExtractor;
import com.devmanchego.javaguard.build.maven.MavenClasspathExtractor;
import com.devmanchego.javaguard.change.SessionLock;
import com.devmanchego.javaguard.cli.ExitCode;
import com.devmanchego.javaguard.cli.Main;
import com.devmanchego.javaguard.config.ConfigException;
import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.project.BuildTool;
import com.devmanchego.javaguard.project.ModuleInfo;
import com.devmanchego.javaguard.project.ProjectDetector;
import com.devmanchego.javaguard.project.ReactorModules;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Builds the classpath cache for the project ahead of time (specification
 * &sect;8.3): so the first POST_EDIT gate of a session does not have to skip
 * in-process compilation while a background warmup catches up. Guarded by a
 * lock file so two concurrent warmups (a human running it, and a gate that
 * would otherwise spawn one because the cache was missing) do not race.
 *
 * <p>Maven only for now (Gradle classpath extraction is Phase 17, matching
 * the project's own Maven-first rollout). A multi-module reactor is warmed
 * one module at a time in a future phase; today this warms only the module
 * the project root itself resolves to.
 *
 * <p>The specification also describes {@code warmup} as building "a findings
 * baseline for the project" - that does not fit Phase 4's actual baseline
 * design (baselines are acquired lazily, per session, per file, on first
 * touch; there is no project-wide, session-less baseline to pre-compute), so
 * this command does not attempt it.
 */
@Command(mixinStandardHelpOptions = true, name = "warmup", description = "Build the classpath cache ahead of time.")
public final class WarmupCommand implements Callable<Integer> {

    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(5);
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    @ParentCommand
    private Main main;

    @Override
    public Integer call() {
        Path root = ProjectRoots.resolve(main);
        JavaGuardPaths paths = new JavaGuardPaths(root);
        paths.ensureLayout();

        try (SessionLock ignored = SessionLock.acquire(paths.classpathCacheDir().resolve(".lock"), LOCK_TIMEOUT)) {
            return warm(root, paths);
        } catch (SessionLock.TimeoutException e) {
            System.out.println("javaguard warmup: another warmup is already in progress for this project.");
            return ExitCode.SUCCESS.code();
        } catch (IOException e) {
            System.err.println("javaguard warmup: " + e.getMessage());
            return ExitCode.ENVIRONMENT_ERROR.code();
        }
    }

    private Integer warm(Path root, JavaGuardPaths paths) {
        JavaGuardConfig config = loadConfig(root);
        Optional<BuildTool> configuredTool = mapBuildTool(config.build().tool());
        Optional<ModuleInfo> module = ProjectDetector.detect(root, configuredTool);
        if (module.isEmpty()) {
            System.err.println("javaguard warmup: no Maven or Gradle project found at " + root);
            return ExitCode.ENVIRONMENT_ERROR.code();
        }
        boolean gradle = module.get().buildTool() == BuildTool.GRADLE;
        List<ModuleInfo> targets = targets(module.get());
        ClasspathCacheStore store = new ClasspathCacheStore(paths.classpathCacheDir());
        List<ModuleInfo> failed = new ArrayList<>();
        int built = 0;
        for (ModuleInfo target : targets) {
            Optional<ClasspathCache> cache = gradle
                    ? new GradleClasspathExtractor().extract(target, config.build().maven().offlineFirst(),
                            config.build().gradle().extraArgs(), System.getenv(), WINDOWS)
                    : new MavenClasspathExtractor().extract(target, config.build().maven().useDaemon(),
                            config.build().maven().offlineFirst(), config.build().maven().extraArgs(), System.getenv(), WINDOWS);
            if (cache.isPresent()) {
                store.put(target.moduleRoot(), cache.get());
                System.out.println("javaguard warmup: classpath cache built for " + target.moduleRoot());
                built++;
            } else {
                failed.add(target);
            }
        }
        if (built == 0) {
            System.err.println("javaguard warmup: could not resolve the classpath (no " + (gradle ? "Gradle" : "Maven")
                    + " executable found, or dependency resolution failed); see " + paths.logFile() + " for details.");
            return ExitCode.ENVIRONMENT_ERROR.code();
        }
        failed.forEach(m -> System.err.println("javaguard warmup: no classpath for " + m.moduleRoot()
                + "; it will be resolved when one of its files is edited."));
        return ExitCode.SUCCESS.code();
    }

    /**
     * The modules worth warming: every module of the reactor that has a main source directory. The root of a multi-module build
     * usually has none (an aggregator, or a Gradle root project without the Java plugin, which has no classpath task at all),
     * so warming only it would cache nothing useful. A single-module project is its own and only target.
     */
    private static List<ModuleInfo> targets(ModuleInfo root) {
        List<ModuleInfo> withSources = new ArrayList<>();
        for (ReactorModules.Module m : ReactorModules.load(root.reactorRoot(), root.buildTool()).all()) {
            if (m.info().mainSourceRoots().stream().anyMatch(Files::isDirectory)) {
                withSources.add(m.info());
            }
        }
        return withSources.isEmpty() ? List.of(root) : withSources;
    }

    private static Optional<BuildTool> mapBuildTool(JavaGuardConfig.BuildTool configured) {
        return switch (configured) {
            case AUTO -> Optional.empty();
            case MAVEN -> Optional.of(BuildTool.MAVEN);
            case GRADLE -> Optional.of(BuildTool.GRADLE);
        };
    }

    /** Configuration errors never block a manual warmup: fall back to defaults. */
    private JavaGuardConfig loadConfig(Path root) {
        Optional<Path> explicitConfig = Optional.ofNullable(main == null ? null : main.configFile);
        ConfigSources sources = ConfigSources.standard(root, explicitConfig,
                Path.of(System.getProperty("user.home")), System.getenv());
        try {
            return ConfigLoader.load(sources).config();
        } catch (ConfigException e) {
            System.err.println("javaguard warmup: " + e.getMessage() + "; using built-in defaults");
            try {
                return ConfigLoader.load(ConfigSources.defaultsOnly()).config();
            } catch (ConfigException impossible) {
                throw new IllegalStateException("Built-in defaults are invalid", impossible);
            }
        }
    }
}
