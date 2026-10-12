package com.devmanchego.javaguard.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The modules of a Maven reactor and who depends on whom (specification &sect;10.3 steps 2-3):
 * which modules a change can break, so tests in them are candidates, and the module paths to hand
 * to {@code -pl}. Built from the {@code <modules>} tree of the reactor root; a dependency counts
 * when its {@code artifactId} is that of another reactor module (group ids are inherited and
 * interpolated, which {@link PomReader} deliberately does not resolve).
 */
public final class ReactorModules {

    /** One module: its detected layout plus the artifact ids it declares a dependency on. */
    public record Module(ModuleInfo info, Optional<String> artifactId, Set<String> dependsOn) {
        public Module {
            dependsOn = Set.copyOf(dependsOn);
        }
    }

    private final Path reactorRoot;
    private final Map<Path, Module> modules;
    private final BuildTool tool;

    private ReactorModules(Path reactorRoot, Map<Path, Module> modules, BuildTool tool) {
        this.reactorRoot = reactorRoot;
        this.modules = modules;
        this.tool = tool;
    }

    /** The reactor of {@code tool} rooted at {@code reactorRoot}. */
    public static ReactorModules load(Path reactorRoot, BuildTool tool) {
        return tool == BuildTool.GRADLE ? loadGradle(reactorRoot) : load(reactorRoot);
    }

    /**
     * The projects of a Gradle build: the root (when it has a build file) and every project {@code settings.gradle(.kts)}
     * includes, each depending on the siblings its build file names with {@code project(":x")}. The module's
     * {@code artifactId} is its project path without the leading colon.
     */
    public static ReactorModules loadGradle(Path reactorRoot) {
        Path root = reactorRoot.toAbsolutePath().normalize();
        Map<Path, Module> found = new LinkedHashMap<>();
        List<String> paths = new ArrayList<>();
        paths.add("");
        paths.addAll(GradleProjects.includedProjects(root));
        for (String path : paths) {
            Path dir = GradleProjects.directoryOf(root, path);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            ProjectDetector.detect(dir, Optional.of(BuildTool.GRADLE)).ifPresent(info -> {
                if (info.moduleRoot().equals(dir)) {
                    found.put(dir, new Module(info, Optional.of(path.isEmpty() ? ":" : path), GradleProjects.projectDependencies(dir)));
                }
            });
        }
        return new ReactorModules(root, found, BuildTool.GRADLE);
    }

    public BuildTool buildTool() {
        return tool;
    }

    /** Walks {@code <modules>} from {@code reactorRoot}; a project without modules is a one-module reactor. */
    public static ReactorModules load(Path reactorRoot) {
        Path root = reactorRoot.toAbsolutePath().normalize();
        Map<Path, Module> found = new LinkedHashMap<>();
        Deque<Path> queue = new ArrayDeque<>(List.of(root));
        Set<Path> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            Path dir = queue.poll();
            if (!seen.add(dir)) {
                continue;
            }
            Optional<MavenPom> pom = PomReader.read(dir.resolve("pom.xml"));
            if (pom.isEmpty()) {
                continue;
            }
            ProjectDetector.detect(dir, Optional.of(BuildTool.MAVEN)).ifPresent(info -> {
                if (info.moduleRoot().equals(dir)) {
                    Set<String> deps = new LinkedHashSet<>();
                    pom.get().dependencies().forEach(c -> deps.add(c.artifactId()));
                    found.put(dir, new Module(info, pom.get().artifactId(), deps));
                }
            });
            for (String module : pom.get().modules()) {
                Path child = dir.resolve(module).normalize();
                queue.add(Files.isDirectory(child) ? child : child.getParent());
            }
        }
        return new ReactorModules(root, found, BuildTool.MAVEN);
    }

    public Path reactorRoot() {
        return reactorRoot;
    }

    public List<Module> all() {
        return List.copyOf(modules.values());
    }

    /** The module owning {@code file} (deepest module root above it). */
    public Optional<Module> moduleOf(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Module best = null;
        for (Module m : modules.values()) {
            Path root = m.info().moduleRoot();
            if (absolute.startsWith(root) && (best == null || root.getNameCount() > best.info().moduleRoot().getNameCount())) {
                best = m;
            }
        }
        return Optional.ofNullable(best);
    }

    /** {@code moduleRoots} plus every module that depends on one of them, directly or transitively. */
    public Set<Path> withDependents(Set<Path> moduleRoots) {
        Set<Path> result = new LinkedHashSet<>(moduleRoots);
        boolean grew = true;
        while (grew) {
            grew = false;
            Set<String> artifacts = new HashSet<>();
            for (Path root : result) {
                Module m = modules.get(root);
                if (m != null) {
                    m.artifactId().ifPresent(artifacts::add);
                }
            }
            for (Module m : modules.values()) {
                if (!result.contains(m.info().moduleRoot()) && m.dependsOn().stream().anyMatch(artifacts::contains)) {
                    result.add(m.info().moduleRoot());
                    grew = true;
                }
            }
        }
        return result;
    }

    /** {@code -pl} argument value for a module: relative path from the reactor root, {@code .} for the root itself. */
    public String selector(Path moduleRoot) {
        Path relative = reactorRoot.relativize(moduleRoot.toAbsolutePath().normalize());
        String text = relative.toString().replace('\\', '/');
        return text.isEmpty() ? "." : text;
    }

    public List<String> selectors(java.util.Collection<Path> moduleRoots) {
        List<String> out = new ArrayList<>();
        for (Path root : moduleRoots) {
            out.add(selector(root));
        }
        return out;
    }
}
