package com.devmanchego.javaguard.build.maven;

import com.devmanchego.javaguard.build.ClasspathCache;
import com.devmanchego.javaguard.change.Hashing;
import com.devmanchego.javaguard.process.ProcessResult;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.project.BuildToolExecutable;
import com.devmanchego.javaguard.project.BuildToolLocator;
import com.devmanchego.javaguard.project.MavenPom;
import com.devmanchego.javaguard.project.ModuleInfo;
import com.devmanchego.javaguard.project.PomReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Extracts one Maven module's classpath by actually invoking Maven
 * (specification &sect;8.3): {@code dependency:build-classpath} for the
 * {@code compile} and {@code test} scopes, run {@code -q -o} (offline) first
 * and, only on failure, again online - never the other way around, so a
 * project with everything already downloaded never pays for a network
 * round-trip.
 *
 * <p><b>Annotation processors</b> (specification &sect;8.2 step 3): this
 * extractor does not parse an explicit {@code annotationProcessorPaths}
 * configuration ({@link ClasspathCache#processorPath()} is always empty for
 * now - a documented scope reduction, spec &sect;10.5-style decision, future
 * work). The common case - Lombok/MapStruct declared as an ordinary
 * {@code provided}-scope {@code <dependency>}, exactly how the project's own
 * {@code maven-spring-jpa-lombok} fixture and Lombok's own official setup
 * instructions do it - already works without this: such a processor is on
 * the compile classpath returned here, and javac's own default (no
 * {@code -proc:} option at all, never {@code -proc:none}) discovers it there
 * through the JDK's default annotation processor lookup, exactly as a real
 * {@code mvn compile} would.
 */
public final class MavenClasspathExtractor {

    private static final Duration OFFLINE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration ONLINE_TIMEOUT = Duration.ofSeconds(120);

    private final ProcessRunner processRunner;

    public MavenClasspathExtractor() {
        this(new ProcessRunner());
    }

    public MavenClasspathExtractor(ProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    /**
     * @param env     process environment ({@code PATH}/{@code PATHEXT} for locating {@code mvn})
     * @param windows whether to resolve Windows executable conventions
     * @return empty when no Maven executable can be found, or either classpath extraction fails
     */
    public Optional<ClasspathCache> extract(ModuleInfo module, boolean useDaemon, boolean offlineFirst,
                                            List<String> extraArgs, Map<String, String> env, boolean windows) {
        Optional<BuildToolExecutable> executable = BuildToolLocator.maven(module.reactorRoot(), useDaemon, env, windows);
        if (executable.isEmpty()) {
            return Optional.empty();
        }
        Optional<List<String>> compileClasspath = buildClasspath(executable.get(), module.moduleRoot(), "compile",
                offlineFirst, extraArgs);
        if (compileClasspath.isEmpty()) {
            return Optional.empty();
        }
        Optional<List<String>> testClasspath = buildClasspath(executable.get(), module.moduleRoot(), "test",
                offlineFirst, extraArgs);
        if (testClasspath.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> buildFileHashes = hashBuildFiles(buildFileChain(module.moduleRoot()));
        return Optional.of(new ClasspathCache(
                compileClasspath.get(),
                testClasspath.get(),
                List.of(),
                module.javaRelease(),
                toAbsoluteStrings(module.mainSourceRoots()),
                toAbsoluteStrings(module.testSourceRoots()),
                buildFileHashes));
    }

    /** Every {@code pom.xml} on the parent chain from {@code moduleRoot} up to (and including) the reactor root. */
    public static List<Path> buildFileChain(Path moduleRoot) {
        List<Path> chain = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        Path current = moduleRoot.toAbsolutePath().normalize();
        while (current != null && seen.add(current)) {
            Path pom = current.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                break;
            }
            chain.add(pom);
            Optional<String> relativePath = PomReader.read(pom).flatMap(MavenPom::parentRelativePath);
            if (relativePath.isEmpty()) {
                break;
            }
            Path resolved = current.resolve(relativePath.get()).normalize();
            current = Files.isDirectory(resolved) ? resolved : resolved.getParent();
        }
        return chain;
    }

    private Map<String, String> hashBuildFiles(List<Path> buildFiles) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Path pom : buildFiles) {
            try {
                hashes.put(pom.toString().replace('\\', '/'), Hashing.sha256(Files.readAllBytes(pom)));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not hash build file " + pom, e);
            }
        }
        return hashes;
    }

    private Optional<List<String>> buildClasspath(BuildToolExecutable executable, Path moduleRoot, String scope,
                                                  boolean offlineFirst, List<String> extraArgs) {
        Path outputFile;
        try {
            outputFile = Files.createTempFile("javaguard-classpath-" + scope, ".txt");
        } catch (IOException e) {
            return Optional.empty();
        }
        try {
            List<String> command = new ArrayList<>(executable.command());
            command.add("dependency:build-classpath");
            command.add("-q");
            command.add("-Dmdep.outputFile=" + outputFile);
            // Not "-Dmdep.includeScope": despite the mdep.* prefix on every other
            // build-classpath property, this one binds to the bare "includeScope"
            // user property (verified against `mvn dependency:help -Dgoal=build-classpath
            // -Ddetail=true`); the mdep.-prefixed spelling is silently ignored, which
            // would otherwise always resolve every scope (the goal's no-filter default).
            command.add("-DincludeScope=" + scope);
            command.addAll(extraArgs);

            if (offlineFirst) {
                List<String> offlineCommand = withOffline(command);
                if (run(offlineCommand, moduleRoot, OFFLINE_TIMEOUT)) {
                    return readClasspathFile(outputFile);
                }
            }
            return run(command, moduleRoot, ONLINE_TIMEOUT) ? readClasspathFile(outputFile) : Optional.empty();
        } finally {
            try {
                Files.deleteIfExists(outputFile);
            } catch (IOException ignored) {
                // Best-effort cleanup of a temp file; leaving it behind is harmless.
            }
        }
    }

    private static List<String> withOffline(List<String> command) {
        List<String> offline = new ArrayList<>(command);
        offline.add("-o");
        return offline;
    }

    private boolean run(List<String> command, Path moduleRoot, Duration timeout) {
        try {
            ProcessResult result = processRunner.run(command, moduleRoot, Map.of(), timeout);
            return result.succeeded();
        } catch (IOException e) {
            return false;
        }
    }

    private Optional<List<String>> readClasspathFile(Path outputFile) {
        if (!Files.isRegularFile(outputFile)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(outputFile, StandardCharsets.UTF_8).strip();
            if (content.isEmpty()) {
                return Optional.of(List.of());
            }
            return Optional.of(List.of(content.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static List<String> toAbsoluteStrings(List<Path> paths) {
        return paths.stream().map(p -> p.toAbsolutePath().normalize().toString()).toList();
    }
}
