package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.build.ClasspathCache;
import com.devmanchego.javaguard.build.ClasspathBuildFiles;
import com.devmanchego.javaguard.build.ClasspathCacheStore;
import com.devmanchego.javaguard.build.gradle.GradleClasspathExtractor;
import com.devmanchego.javaguard.build.maven.MavenClasspathExtractor;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.project.BuildTool;
import com.devmanchego.javaguard.project.ModuleInfo;
import com.devmanchego.javaguard.state.JavaGuardPaths;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Rebuilds a module's classpath cache after the end-of-turn gate has built it for real (specification
 * &sect;8.3: "stale entries are rebuilt by the end-of-turn gate"). This is also how a project that never ran
 * {@code javaguard warmup} gets its in-process compilation switched on.
 */
@FunctionalInterface
public interface ClasspathRefresher {

    /** Rebuilds the cache of {@code module} when it is missing or stale and {@code remaining} allows it. */
    void refreshIfStale(ModuleInfo module, Duration remaining);

    static ClasspathRefresher none() {
        return (module, remaining) -> { };
    }

    /** Maven or Gradle, whichever builds the module; needs a minute of budget, extraction is slow. */
    static ClasspathRefresher maven(JavaGuardPaths paths, JavaGuardConfig config, Map<String, String> env) {
        Logger log = Logger.getLogger(ClasspathRefresher.class.getName());
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return (module, remaining) -> {
            if (remaining.compareTo(Duration.ofSeconds(60)) < 0) {
                return;
            }
            ClasspathCacheStore store = new ClasspathCacheStore(paths.classpathCacheDir());
            Optional<ClasspathCache> cached = store.get(module.moduleRoot());
            if (cached.isPresent() && !store.isStale(cached.get(), ClasspathBuildFiles.of(module))) {
                return;
            }
            try {
                Optional<ClasspathCache> extracted = module.buildTool() == BuildTool.GRADLE
                        ? new GradleClasspathExtractor().extract(module, config.build().maven().offlineFirst(),
                                config.build().gradle().extraArgs(), env, windows)
                        : new MavenClasspathExtractor().extract(module, config.build().maven().useDaemon(),
                                config.build().maven().offlineFirst(), config.build().maven().extraArgs(), env, windows);
                extracted.ifPresent(cache -> store.put(module.moduleRoot(), cache));
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Could not refresh the classpath cache of " + module.moduleRoot(), e);
            }
        };
    }
}
