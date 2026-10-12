package com.devmanchego.javaguard.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 6: the classpath cache round-trips and detects staleness (specification section 8.3). */
class ClasspathCacheStoreTest {

    @TempDir
    Path cacheDir;

    @TempDir
    Path projectDir;

    private ClasspathCacheStore store() {
        return new ClasspathCacheStore(cacheDir);
    }

    @Test
    void getReturnsEmptyWhenNoCacheFileExists() {
        assertThat(store().get(projectDir)).isEmpty();
    }

    @Test
    void putThenGetRoundTrips() {
        ClasspathCache cache = new ClasspathCache(
                List.of("/deps/a.jar"), List.of("/deps/a.jar", "/deps/junit.jar"), List.of(), 21,
                List.of("/proj/src/main/java"), List.of("/proj/src/test/java"), Map.of("/proj/pom.xml", "abc123"));

        store().put(projectDir, cache);

        assertThat(store().get(projectDir)).contains(cache);
    }

    @Test
    void differentModuleRootsGetDifferentCacheFiles() {
        ClasspathCache a = new ClasspathCache(List.of("/a.jar"), List.of(), List.of(), 21, List.of(), List.of(), Map.of());
        ClasspathCache b = new ClasspathCache(List.of("/b.jar"), List.of(), List.of(), 21, List.of(), List.of(), Map.of());

        store().put(projectDir.resolve("module-a"), a);
        store().put(projectDir.resolve("module-b"), b);

        assertThat(store().get(projectDir.resolve("module-a"))).contains(a);
        assertThat(store().get(projectDir.resolve("module-b"))).contains(b);
    }

    @Test
    void isStaleWhenABuildFileHashChanged() throws IOException {
        Path pom = projectDir.resolve("pom.xml");
        Files.writeString(pom, "<project>v1</project>", StandardCharsets.UTF_8);
        String hash = ClasspathCacheStore.hashOf(pom).orElseThrow();
        ClasspathCache cache = new ClasspathCache(List.of(), List.of(), List.of(), 21, List.of(), List.of(),
                Map.of(pom.toAbsolutePath().normalize().toString().replace('\\', '/'), hash));

        assertThat(store().isStale(cache, List.of(pom))).isFalse();

        Files.writeString(pom, "<project>v2</project>", StandardCharsets.UTF_8);

        assertThat(store().isStale(cache, List.of(pom))).isTrue();
    }

    @Test
    void isStaleWhenABuildFileIsMissing() throws IOException {
        Path pom = projectDir.resolve("pom.xml");
        Files.writeString(pom, "<project/>", StandardCharsets.UTF_8);
        String hash = ClasspathCacheStore.hashOf(pom).orElseThrow();
        ClasspathCache cache = new ClasspathCache(List.of(), List.of(), List.of(), 21, List.of(), List.of(),
                Map.of(pom.toAbsolutePath().normalize().toString().replace('\\', '/'), hash));

        Files.delete(pom);

        assertThat(store().isStale(cache, List.of(pom))).isTrue();
    }

    @Test
    void isStaleWhenANewBuildFileAppearsOnTheChain() throws IOException {
        Path moduleDir = Files.createDirectory(projectDir.resolve("module"));
        Path pom = moduleDir.resolve("pom.xml");
        Files.writeString(pom, "<project/>", StandardCharsets.UTF_8);
        String hash = ClasspathCacheStore.hashOf(pom).orElseThrow();
        ClasspathCache cache = new ClasspathCache(List.of(), List.of(), List.of(), 21, List.of(), List.of(),
                Map.of(pom.toAbsolutePath().normalize().toString().replace('\\', '/'), hash));

        Path parentPom = projectDir.resolve("pom.xml");
        Files.writeString(parentPom, "<project/>", StandardCharsets.UTF_8);

        assertThat(store().isStale(cache, List.of(pom, parentPom))).isTrue();
    }
}
