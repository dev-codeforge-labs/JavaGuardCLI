package com.devmanchego.javaguard.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathNormalizerTest {

    @ParameterizedTest
    @CsvSource({
            "/c/work/demo/Foo.java,      C:/work/demo/Foo.java",
            "/C/work/demo,               C:/work/demo",
            "/d/,                        D:/",
            "/cygdrive/e/src/A.java,     E:/src/A.java",
            "C:/already/windows,         C:/already/windows",
            "/usr/local/x,               /usr/local/x",
            "/cc/not-a-drive,            /cc/not-a-drive",
            "relative/path,              relative/path",
    })
    void msysDrivePathsAreConvertedOnWindowsOnly(String raw, String windowsExpected) {
        assertThat(new PathNormalizer(true).toNativeString(raw)).isEqualTo(windowsExpected);
        assertThat(new PathNormalizer(false).toNativeString(raw)).isEqualTo(raw);
    }

    @Test
    void relativePathsResolveAgainstBaseAndNormalize(@TempDir Path base) {
        Path resolved = PathNormalizer.forCurrentOs().resolve("src/../src/./A.java", base);

        assertThat(resolved).isEqualTo(base.resolve("src").resolve("A.java"));
        assertThat(resolved.isAbsolute()).isTrue();
    }

    @Test
    void absolutePathsIgnoreBase(@TempDir Path base, @TempDir Path other) {
        Path resolved = PathNormalizer.forCurrentOs().resolve(other.resolve("A.java").toString(), base);

        assertThat(resolved).isEqualTo(other.resolve("A.java"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void backslashAndForwardSlashPathsAreEquivalentOnWindows() {
        PathNormalizer normalizer = new PathNormalizer(true);
        Path base = Path.of("C:\\work");

        assertThat(normalizer.resolve("C:\\work\\demo\\A.java", base))
                .isEqualTo(normalizer.resolve("C:/work/demo/A.java", base));
        assertThat(normalizer.resolve("/c/work/demo/A.java", base))
                .isEqualTo(Path.of("C:\\work\\demo\\A.java"));
    }

    @Test
    void blankPathIsRejected(@TempDir Path base) {
        assertThatThrownBy(() -> PathNormalizer.forCurrentOs().resolve("  ", base))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
