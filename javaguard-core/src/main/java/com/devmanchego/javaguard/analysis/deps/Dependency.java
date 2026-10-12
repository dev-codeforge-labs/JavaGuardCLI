package com.devmanchego.javaguard.analysis.deps;

import java.util.Objects;

/**
 * A dependency as a build file declares it. {@code version} is empty when the build file leaves it to a parent, a BOM
 * or a platform (nothing to compare and nothing to ask a vulnerability database); a {@code ${property}} that the same
 * file defines is already substituted.
 *
 * @param scope Maven {@code <scope>} or Gradle configuration name; informational
 */
public record Dependency(String groupId, String artifactId, String version, String scope) {

    public Dependency {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
        version = version == null ? "" : version.strip();
        scope = scope == null ? "" : scope;
    }

    /** {@code group:artifact}, the identity across versions. */
    public String key() {
        return groupId + ":" + artifactId;
    }

    public String coordinates() {
        return version.isEmpty() ? key() : key() + ":" + version;
    }
}
