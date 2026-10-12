package com.devmanchego.javaguard.analysis.deps;

import java.io.IOException;
import java.util.List;
import java.util.OptionalDouble;

/**
 * A vulnerability database lookup (OSV, specification &sect;11.4). The boundary of the dependency analysis: tests record
 * real responses and serve them to {@link HttpOsvClient}, or replace this interface outright.
 */
public interface OsvClient {

    /**
     * @param dependency the queried coordinate
     * @param id         the advisory id (GHSA-..., CVE-...)
     * @param summary    one line, may be empty
     * @param score      CVSS base score when known
     */
    record Vulnerability(Dependency dependency, String id, String summary, OptionalDouble score) {
    }

    /** Known vulnerabilities of the given versions; coordinates without any are simply absent. Only coordinates are sent. */
    List<Vulnerability> query(List<Dependency> dependencies) throws IOException, InterruptedException;
}
