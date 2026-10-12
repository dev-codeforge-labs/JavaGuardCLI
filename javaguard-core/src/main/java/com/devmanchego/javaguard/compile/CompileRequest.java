package com.devmanchego.javaguard.compile;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What to compile and with what (specification &sect;8.2 step 2): the
 * changed {@code .java} files, the release to target, an already-assembled
 * classpath (compiled classes directory/directories plus resolved
 * dependency jars - assembling that from a {@link com.devmanchego.javaguard.build.ClasspathCache}
 * for the compile-vs-test-scope distinction is the caller's job, not this
 * request's), the source roots for {@code -sourcepath} (so an uncompiled
 * sibling resolves), and the output directory (must be under
 * {@code .javaguard/cache/tmp/} - JavaGuard never writes to {@code target/}
 * or {@code build/}).
 *
 * @param projectRoot        used to make a diagnostic's file project-relative
 * @param errorProneEnabled  when false, plain {@code javax.tools} compilation only
 */
public record CompileRequest(
        List<Path> filesToCompile,
        int release,
        List<String> classpath,
        List<Path> sourceRoots,
        Path outputDir,
        Path projectRoot,
        boolean errorProneEnabled
) {

    public CompileRequest {
        filesToCompile = List.copyOf(filesToCompile);
        if (filesToCompile.isEmpty()) {
            throw new IllegalArgumentException("filesToCompile must not be empty");
        }
        classpath = List.copyOf(classpath);
        sourceRoots = List.copyOf(sourceRoots);
        Objects.requireNonNull(outputDir, "outputDir");
        Objects.requireNonNull(projectRoot, "projectRoot");
    }
}
