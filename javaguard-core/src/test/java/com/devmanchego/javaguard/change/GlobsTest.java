package com.devmanchego.javaguard.change;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Globs over '/'-separated project-relative paths, identical on every OS. */
class GlobsTest {

    @ParameterizedTest(name = "{0} vs {1} -> {2}")
    @CsvSource({
            "**/generated/**,     src/generated/A.java,           true",
            "**/generated/**,     generated/A.java,               true",
            "**/generated/**,     src/main/java/Generated.java,   false",
            "**/target/**,        target/classes/A.class,         true",
            "**/cli/**,           src/main/java/com/acme/cli/Main.java, true",
            "src/*.java,          src/A.java,                     true",
            "src/*.java,          src/sub/A.java,                 false",
            "src/**/*.java,       src/A.java,                     true",
            "src/**/*.java,       src/a/b/A.java,                 true",
            "**/*Test.java,       src/test/java/FooTest.java,     true",
            "**/*Test.java,       src/test/java/FooTests.java,    false",
            "A?.java,             AB.java,                        true",
            "A?.java,             A/.java,                        false",
            "legacy/**,           legacy,                         false",
            "legacy/**,           legacy/x/Y.java,                true",
            "src/main/java/a.b/C.java, src/main/java/a.b/C.java,  true",
            "src/main/java/a.b/C.java, src/main/java/aXb/C.java,  false",
    })
    void matches(String glob, String path, boolean expected) {
        assertThat(Globs.matches(glob, path)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"src\\main\\A.java", "src/main/A.java"})
    void backslashesInThePathAreTreatedAsSeparators(String path) {
        assertThat(Globs.matches("src/**/A.java", path)).isTrue();
    }
}
