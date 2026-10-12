package com.devmanchego.javaguard.integrity;

/**
 * Whether a project-relative path is a test source. Matches the convention the AST engine uses: a file under
 * a {@code src/test} directory, or one named {@code *Test}, {@code *Tests} or {@code *IT}.
 */
final class TestSourcePaths {

    private TestSourcePaths() {
    }

    static boolean isTestSource(String relativePath) {
        String p = relativePath.replace('\\', '/');
        return p.contains("/src/test/") || p.startsWith("src/test/") || p.endsWith("Test.java") || p.endsWith("Tests.java")
                || p.endsWith("IT.java");
    }
}
