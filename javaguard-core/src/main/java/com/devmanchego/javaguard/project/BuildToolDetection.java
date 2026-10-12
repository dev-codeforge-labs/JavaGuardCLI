package com.devmanchego.javaguard.project;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of detecting the build tool for one directory (specification
 * &sect;7.2).
 *
 * @param tool      the detected tool, empty when neither a {@code pom.xml}
 *                  nor a Gradle build file is present
 * @param ambiguous true when both a {@code pom.xml} and a Gradle build file
 *                  are present and {@code tool} was picked by configuration
 *                  ({@code build.tool}, default Maven); {@code doctor} should
 *                  warn about this
 */
public record BuildToolDetection(Optional<BuildTool> tool, boolean ambiguous) {

    public BuildToolDetection {
        Objects.requireNonNull(tool, "tool");
    }

    public static BuildToolDetection none() {
        return new BuildToolDetection(Optional.empty(), false);
    }

    public static BuildToolDetection of(BuildTool tool, boolean ambiguous) {
        return new BuildToolDetection(Optional.of(tool), ambiguous);
    }
}
