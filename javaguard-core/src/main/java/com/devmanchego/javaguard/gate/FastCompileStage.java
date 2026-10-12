package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.build.ClasspathCache;
import com.devmanchego.javaguard.build.ClasspathCacheStore;
import com.devmanchego.javaguard.build.ClasspathBuildFiles;
import com.devmanchego.javaguard.compile.CompileRequest;
import com.devmanchego.javaguard.compile.CompileResult;
import com.devmanchego.javaguard.compile.InProcessCompiler;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.project.BuildTool;
import com.devmanchego.javaguard.project.ModuleInfo;
import com.devmanchego.javaguard.state.JavaGuardPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * In-process compilation of the edited files of one module (specification
 * &sect;8.2, &sect;10.2 step 5). Never writes under {@code target/} or {@code build/}:
 * class output goes to a throwaway directory under {@code .javaguard/cache/tmp/}.
 *
 * <p>A missing or stale classpath cache, a non-Maven module or a compiler
 * failure each skip compilation with an explanatory note; none of them is an
 * error for the gate (fail-open, specification &sect;1.1).
 */
final class FastCompileStage {

    private static final Logger LOG = Logger.getLogger(FastCompileStage.class.getName());

    /** Findings of the stage plus the notes explaining anything that was skipped. */
    record Outcome(List<Finding> findings, List<String> notes) {
        Outcome {
            findings = List.copyOf(findings);
            notes = List.copyOf(notes);
        }

        static Outcome skipped(String note) {
            return new Outcome(List.of(), List.of(note));
        }
    }

    private final JavaGuardPaths paths;
    private final boolean errorProneEnabled;
    private final InProcessCompiler compiler = new InProcessCompiler();

    FastCompileStage(JavaGuardPaths paths, boolean errorProneEnabled) {
        this.paths = paths;
        this.errorProneEnabled = errorProneEnabled;
    }

    /**
     * Compiles {@code files} (absolute paths, all belonging to {@code module}). Test sources are
     * compiled with the test classpath, main sources with the compile classpath.
     */
    Outcome compile(ModuleInfo module, List<Path> files) {
        Optional<ClasspathCache> cached = new ClasspathCacheStore(paths.classpathCacheDir()).get(module.moduleRoot());
        if (cached.isEmpty()) {
            return Outcome.skipped("In-process compilation skipped for " + display(module)
                    + ": the classpath cache is missing. Run 'javaguard warmup' once.");
        }
        ClasspathCacheStore store = new ClasspathCacheStore(paths.classpathCacheDir());
        if (store.isStale(cached.get(), ClasspathBuildFiles.of(module))) {
            return Outcome.skipped("In-process compilation skipped for " + display(module)
                    + ": a build file changed since the classpath cache was built. Run 'javaguard warmup'.");
        }
        ClasspathCache cache = cached.get();

        List<Path> testFiles = files.stream().filter(module::isTestSource).toList();
        List<Path> mainFiles = files.stream().filter(f -> !module.isTestSource(f)).toList();
        List<Finding> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        compileGroup(module, cache, mainFiles, false, findings, notes);
        compileGroup(module, cache, testFiles, true, findings, notes);
        return new Outcome(findings, notes);
    }

    private void compileGroup(ModuleInfo module, ClasspathCache cache, List<Path> files, boolean tests,
                              List<Finding> findings, List<String> notes) {
        if (files.isEmpty()) {
            return;
        }
        Path outputDir = null;
        try {
            Files.createDirectories(paths.tmpDir());
            outputDir = Files.createTempDirectory(paths.tmpDir(), "javac-");
            List<String> classpath = new ArrayList<>(tests ? cache.testClasspath() : cache.compileClasspath());
            boolean gradle = module.buildTool() == BuildTool.GRADLE;
            addIfDirectory(classpath, module.moduleRoot().resolve(gradle ? "build/classes/java/main" : "target/classes"));
            if (tests) {
                addIfDirectory(classpath, module.moduleRoot().resolve(gradle ? "build/classes/java/test" : "target/test-classes"));
            }
            List<Path> sourceRoots = new ArrayList<>(module.mainSourceRoots());
            sourceRoots.addAll(module.testSourceRoots());
            sourceRoots.removeIf(root -> !Files.isDirectory(root));
            CompileResult result = compiler.compile(new CompileRequest(files, module.javaRelease(), classpath,
                    sourceRoots, outputDir, paths.projectRoot(), errorProneEnabled));
            Set<Path> requested = Set.copyOf(files.stream().map(f -> paths.projectRoot().relativize(f)).toList());
            // -sourcepath compiles referenced siblings too; only the edited files are this gate's business.
            result.findings().stream()
                    .filter(f -> f.file().map(requested::contains).orElse(false))
                    .forEach(findings::add);
        } catch (IOException | RuntimeException | LinkageError e) {
            LOG.log(Level.WARNING, "In-process compilation failed for " + display(module), e);
            notes.add("In-process compilation skipped for " + display(module) + ": the compiler failed (see the log).");
        } finally {
            deleteQuietly(outputDir);
        }
    }

    private static void addIfDirectory(List<String> classpath, Path dir) {
        if (Files.isDirectory(dir)) {
            classpath.add(dir.toString());
        }
    }

    private String display(ModuleInfo module) {
        Path relative = paths.projectRoot().relativize(module.moduleRoot().toAbsolutePath().normalize());
        String text = relative.toString().replace('\\', '/');
        return text.isEmpty() ? "the project root module" : "module " + text;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Throwaway directory: a leftover is harmless and cleaned by 'state clear'.
                }
            });
        } catch (IOException ignored) {
            // Same as above.
        }
    }
}
