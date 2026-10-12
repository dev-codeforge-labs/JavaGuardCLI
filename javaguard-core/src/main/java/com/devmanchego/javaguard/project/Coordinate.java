package com.devmanchego.javaguard.project;

import java.util.Objects;

/** Minimal Maven/Gradle dependency identity used for framework detection. */
public record Coordinate(String groupId, String artifactId) {

    public Coordinate {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
    }
}
