package com.devmanchego.javaguard.project;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The handful of {@code pom.xml} elements JavaGuard's own detection needs.
 * Not a Maven model: no inheritance, no interpolation, no effective-POM
 * resolution (that happens through the real Maven invocation in Phase 6/8).
 * Produced only by {@link PomReader}, which is tolerant of anything else in
 * the file.
 *
 * @param parentRelativePath path to the parent POM, resolved to Maven's own
 *                            default ({@code ../pom.xml}) when {@code <parent>}
 *                            is present without an explicit {@code <relativePath>};
 *                            empty when there is no {@code <parent>}
 * @param modules             {@code <modules><module>} entries, in document order
 * @param sourceDirectory     {@code <build><sourceDirectory>}, when overridden
 * @param testSourceDirectory {@code <build><testSourceDirectory>}, when overridden
 * @param properties          {@code <properties>} direct children, tag name to text
 * @param compilerPluginRelease {@code maven-compiler-plugin} configuration {@code <release>}
 * @param compilerPluginSource  {@code maven-compiler-plugin} configuration {@code <source>}
 * @param compilerPluginTarget  {@code maven-compiler-plugin} configuration {@code <target>}
 * @param dependencies        {@code <dependencies><dependency>} entries (not {@code dependencyManagement}:
 *                            declared availability is not the same as actual use)
 * @param artifactId          the project's own {@code <artifactId>}, to match reactor dependents
 */
public record MavenPom(
        Optional<String> parentRelativePath,
        List<String> modules,
        Optional<String> sourceDirectory,
        Optional<String> testSourceDirectory,
        Map<String, String> properties,
        Optional<String> compilerPluginRelease,
        Optional<String> compilerPluginSource,
        Optional<String> compilerPluginTarget,
        Set<Coordinate> dependencies,
        Optional<String> artifactId
) {

    public MavenPom {
        Objects.requireNonNull(parentRelativePath, "parentRelativePath");
        modules = List.copyOf(modules);
        Objects.requireNonNull(sourceDirectory, "sourceDirectory");
        Objects.requireNonNull(testSourceDirectory, "testSourceDirectory");
        properties = Map.copyOf(properties);
        Objects.requireNonNull(compilerPluginRelease, "compilerPluginRelease");
        Objects.requireNonNull(compilerPluginSource, "compilerPluginSource");
        Objects.requireNonNull(compilerPluginTarget, "compilerPluginTarget");
        dependencies = Set.copyOf(dependencies);
        Objects.requireNonNull(artifactId, "artifactId");
    }
}
