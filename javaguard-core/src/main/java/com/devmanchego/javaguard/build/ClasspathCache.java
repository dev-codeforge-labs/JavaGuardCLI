package com.devmanchego.javaguard.build;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the in-process compiler needs to compile one module without
 * starting Maven/Gradle (specification &sect;8.3): the resolved classpaths,
 * the release to compile with, the source roots (for {@code -sourcepath}),
 * and the SHA-256 of every build file that influenced this answer, used to
 * detect staleness. Serialized as-is to
 * {@code .javaguard/cache/classpath/<module-hash>.json} by
 * {@link ClasspathCacheStore}; every path is a plain string (absolute), not
 * a {@code java.nio.file.Path}, so it round-trips through Jackson with no
 * extra module.
 *
 * @param compileClasspath resolved {@code compile}-scope dependency jars, absolute paths
 * @param testClasspath    resolved {@code test}-scope dependency jars (superset of
 *                         {@code compileClasspath}), absolute paths
 * @param processorPath    annotation processor jars declared via an explicit
 *                         {@code annotationProcessorPaths} configuration; empty when
 *                         none is declared (processors are then discovered from the
 *                         compile classpath itself, e.g. Lombok on {@code provided}
 *                         scope - see build.maven.MavenClasspathExtractor's Javadoc)
 * @param release          the {@code --release} value to compile with
 * @param mainSourceRoots  absolute main source roots (for {@code -sourcepath})
 * @param testSourceRoots  absolute test source roots (for {@code -sourcepath})
 * @param buildFileHashes  absolute build file path to hex SHA-256, every file that
 *                         influenced this classpath (the module's own {@code pom.xml}
 *                         and every ancestor up to the reactor root)
 */
public record ClasspathCache(
        List<String> compileClasspath,
        List<String> testClasspath,
        List<String> processorPath,
        int release,
        List<String> mainSourceRoots,
        List<String> testSourceRoots,
        Map<String, String> buildFileHashes
) {

    public ClasspathCache {
        compileClasspath = List.copyOf(compileClasspath);
        testClasspath = List.copyOf(testClasspath);
        processorPath = List.copyOf(processorPath);
        mainSourceRoots = List.copyOf(mainSourceRoots);
        testSourceRoots = List.copyOf(testSourceRoots);
        buildFileHashes = Map.copyOf(Objects.requireNonNull(buildFileHashes, "buildFileHashes"));
    }
}
