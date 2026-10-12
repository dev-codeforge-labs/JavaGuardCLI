package com.devmanchego.javaguard.build.gradle;

import com.devmanchego.javaguard.build.ClasspathCache;
import com.devmanchego.javaguard.change.Hashing;
import com.devmanchego.javaguard.process.ProcessResult;
import com.devmanchego.javaguard.process.ProcessRunner;
import com.devmanchego.javaguard.project.BuildToolExecutable;
import com.devmanchego.javaguard.project.BuildToolLocator;
import com.devmanchego.javaguard.project.GradleProjects;
import com.devmanchego.javaguard.project.ModuleInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts one Gradle module's classpath by running Gradle (specification &sect;8.3), the counterpart of
 * {@code MavenClasspathExtractor}: JavaGuard's init script registers {@code javaguardClasspath} on every Java project,
 * which writes the {@code main} compile classpath and the {@code test} runtime classpath to a file. Offline first, online
 * only when that fails. Entries under the module's own {@code build/} directory are dropped: they are the module's own
 * compiled output, which the in-process compiler must not mistake for dependencies (sibling projects' outputs stay,
 * they are dependencies). An explicit annotation-processor path is not read, as for Maven: processors declared on
 * {@code compileOnly} are found on the compile classpath.
 */
public final class GradleClasspathExtractor {

    private static final Duration OFFLINE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration ONLINE_TIMEOUT = Duration.ofSeconds(180);

    private final ProcessRunner processRunner;

    public GradleClasspathExtractor() {
        this(new ProcessRunner());
    }

    public GradleClasspathExtractor(ProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    /** Empty when no Gradle executable is found or the extraction fails offline and online. */
    public Optional<ClasspathCache> extract(ModuleInfo module, boolean offlineFirst, List<String> extraArgs, Map<String, String> env,
                                            boolean windows) {
        Optional<BuildToolExecutable> executable = BuildToolLocator.gradle(module.reactorRoot(), env, windows);
        if (executable.isEmpty()) {
            return Optional.empty();
        }
        Path outputFile;
        try {
            outputFile = Files.createTempFile("javaguard-classpath", ".txt");
        } catch (IOException e) {
            return Optional.empty();
        }
        try (GradleInitScript init = GradleInitScript.create()) {
            Optional<Map<String, List<String>>> classpaths = run(executable.get(), module, init, outputFile, offlineFirst, extraArgs);
            if (classpaths.isEmpty()) {
                return Optional.empty();
            }
            Path ownBuild = module.moduleRoot().resolve("build").toAbsolutePath().normalize();
            return Optional.of(new ClasspathCache(
                    withoutOwnOutput(classpaths.get().get("compile"), ownBuild),
                    withoutOwnOutput(classpaths.get().get("test"), ownBuild),
                    List.of(),
                    module.javaRelease(),
                    module.mainSourceRoots().stream().map(p -> p.toAbsolutePath().toString()).toList(),
                    module.testSourceRoots().stream().map(p -> p.toAbsolutePath().toString()).toList(),
                    hash(buildFileChain(module))));
        } finally {
            try {
                Files.deleteIfExists(outputFile);
            } catch (IOException e) {
                // A leftover temp file is harmless.
            }
        }
    }

    private Optional<Map<String, List<String>>> run(BuildToolExecutable executable, ModuleInfo module, GradleInitScript init,
                                                    Path outputFile, boolean offlineFirst, List<String> extraArgs) {
        for (boolean offline : offlineFirst ? new boolean[] {true, false} : new boolean[] {false}) {
            List<String> command = new ArrayList<>(executable.command());
            command.addAll(List.of("-q", "--console=plain"));
            if (offline) {
                command.add("--offline");
            }
            command.addAll(List.of("--init-script", init.path().toString(), "-Djavaguard.out=" + outputFile));
            command.addAll(extraArgs);
            command.add(GradleProjects.taskPrefix(module.reactorRoot(), module.moduleRoot()) + "javaguardClasspath");
            try {
                ProcessResult result = processRunner.run(command, module.reactorRoot(), Map.of(),
                        offline ? OFFLINE_TIMEOUT : ONLINE_TIMEOUT);
                if (result.succeeded()) {
                    Optional<Map<String, List<String>>> parsed = parse(outputFile);
                    if (parsed.isPresent()) {
                        return parsed;
                    }
                }
            } catch (IOException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** {@code compile=...} and {@code test=...} lines, entries separated by the platform path separator. */
    static Optional<Map<String, List<String>>> parse(Path file) {
        try {
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    List<String> entries = new ArrayList<>();
                    for (String entry : line.substring(eq + 1).split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                        if (!entry.isBlank()) {
                            entries.add(entry.strip());
                        }
                    }
                    out.put(line.substring(0, eq), entries);
                }
            }
            return out.containsKey("compile") && out.containsKey("test") ? Optional.of(out) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static List<String> withoutOwnOutput(List<String> entries, Path ownBuild) {
        return entries.stream().filter(e -> !Path.of(e).toAbsolutePath().normalize().startsWith(ownBuild)).toList();
    }

    /**
     * Every build file that can change this module's classpath: {@code build.gradle(.kts)} and {@code gradle.properties}
     * of the module and of each directory up to the reactor root, the root's {@code settings.gradle(.kts)} and the version
     * catalog {@code gradle/libs.versions.toml}.
     */
    public static List<Path> buildFileChain(ModuleInfo module) {
        List<Path> chain = new ArrayList<>();
        Path reactor = module.reactorRoot().toAbsolutePath().normalize();
        for (Path dir = module.moduleRoot().toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            for (String name : List.of("build.gradle.kts", "build.gradle", "gradle.properties")) {
                addIfFile(chain, dir.resolve(name));
            }
            if (dir.equals(reactor)) {
                for (String name : List.of("settings.gradle.kts", "settings.gradle", "gradle/libs.versions.toml")) {
                    addIfFile(chain, dir.resolve(name));
                }
                break;
            }
        }
        return chain;
    }

    private static void addIfFile(List<Path> chain, Path file) {
        if (Files.isRegularFile(file)) {
            chain.add(file);
        }
    }

    private static Map<String, String> hash(List<Path> files) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Path file : files) {
            try {
                hashes.put(file.toString().replace('\\', '/'), Hashing.sha256(Files.readAllBytes(file)));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not hash build file " + file, e);
            }
        }
        return hashes;
    }
}
