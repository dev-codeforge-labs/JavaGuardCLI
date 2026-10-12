package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.build.ClasspathCacheStore;
import com.devmanchego.javaguard.cli.ExitCode;
import com.devmanchego.javaguard.cli.Main;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 6: {@code javaguard warmup} builds and persists the classpath cache. */
class WarmupCommandTest {

    @TempDir
    Path root;

    private PrintStream originalOut;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        originalOut = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        Files.writeString(root.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.acme</groupId>
                    <artifactId>warmup-fixture</artifactId>
                    <version>1.0.0</version>
                    <packaging>jar</packaging>
                    <properties>
                        <maven.compiler.release>21</maven.compiler.release>
                    </properties>
                </project>
                """, StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    @Test
    void buildsAndPersistsTheClasspathCache() {
        int exit = new CommandLine(new Main()).execute("--project-dir", root.toString(), "warmup");

        assertThat(exit).isEqualTo(ExitCode.SUCCESS.code());
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("classpath cache built");

        JavaGuardPaths paths = new JavaGuardPaths(root);
        assertThat(new ClasspathCacheStore(paths.classpathCacheDir()).get(root)).isPresent();
    }

    @Test
    void aMultiModuleBuildWarmsEveryModuleWithSourcesAndNotTheAggregatorRoot() throws IOException {
        Files.writeString(root.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.acme</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <properties>
                        <maven.compiler.release>21</maven.compiler.release>
                    </properties>
                    <modules>
                        <module>core</module>
                        <module>web</module>
                    </modules>
                </project>
                """, StandardCharsets.UTF_8);
        for (String name : new String[] {"core", "web"}) {
            Path module = Files.createDirectories(root.resolve(name));
            Files.createDirectories(module.resolve("src/main/java"));
            Files.writeString(module.resolve("pom.xml"), """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <modelVersion>4.0.0</modelVersion>
                        <parent>
                            <groupId>com.acme</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0.0</version>
                        </parent>
                        <artifactId>%s</artifactId>
                    </project>
                    """.formatted(name), StandardCharsets.UTF_8);
        }

        int exit = new CommandLine(new Main()).execute("--project-dir", root.toString(), "warmup");

        assertThat(exit).isEqualTo(ExitCode.SUCCESS.code());
        ClasspathCacheStore store = new ClasspathCacheStore(new JavaGuardPaths(root).classpathCacheDir());
        assertThat(store.get(root.resolve("core"))).isPresent();
        assertThat(store.get(root.resolve("web"))).isPresent();
        assertThat(store.get(root)).as("the aggregator has no sources and no classpath of its own").isEmpty();
    }

    @Test
    void secondWarmupReusesTheSameCacheFile() {
        new CommandLine(new Main()).execute("--project-dir", root.toString(), "warmup");
        int secondExit = new CommandLine(new Main()).execute("--project-dir", root.toString(), "warmup");

        assertThat(secondExit).isEqualTo(ExitCode.SUCCESS.code());
        JavaGuardPaths paths = new JavaGuardPaths(root);
        assertThat(new ClasspathCacheStore(paths.classpathCacheDir()).get(root)).isPresent();
    }
}
