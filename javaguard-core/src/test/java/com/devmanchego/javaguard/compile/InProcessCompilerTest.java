package com.devmanchego.javaguard.compile;

import com.devmanchego.javaguard.build.ClasspathCache;
import com.devmanchego.javaguard.build.maven.MavenClasspathExtractor;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.BuildTool;
import com.devmanchego.javaguard.project.ModuleInfo;
import com.devmanchego.javaguard.project.ProjectDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 6: in-process compilation (specification &sect;8.2). A broken
 * file yields {@code JAVAC} with symbol/location detail, a seeded
 * {@code BigDecimalEquals} yields {@code EP:BigDecimalEquals}, annotation
 * processing (Lombok, via the real classpath extracted from the project's
 * own {@code maven-spring-jpa-lombok} fixture) is honored, and nothing is
 * ever written under {@code target/}.
 */
class InProcessCompilerTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures").toAbsolutePath().normalize();
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private final InProcessCompiler compiler = new InProcessCompiler();

    @TempDir
    Path projectRoot;

    @TempDir
    Path outputDir;

    private Path writeSource(String relativePath, String content) throws IOException {
        Path file = projectRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void brokenFileYieldsJavacFindingWithSymbolAndLocationDetail() throws IOException {
        Path file = writeSource("Bad.java", """
                class Bad {
                    void m() {
                        int x = doesNotExist();
                    }
                }
                """);

        CompileResult result = compiler.compile(new CompileRequest(
                List.of(file), 21, List.of(), List.of(projectRoot), outputDir, projectRoot, false));

        assertThat(result.success()).isFalse();
        Finding finding = result.findings().stream().filter(f -> f.ruleId().equals("JAVAC")).findFirst()
                .orElseThrow(() -> new AssertionError("Expected a JAVAC finding, got: " + result.findings()));
        assertThat(finding.category()).isEqualTo(Category.COMPILATION);
        assertThat(finding.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(finding.engine()).isEqualTo("javac");
        assertThat(finding.file()).contains(Path.of("Bad.java"));
        assertThat(finding.line()).isEqualTo(3);
        assertThat(finding.message()).contains("cannot find symbol");
        assertThat(finding.detail()).containsEntry("symbol", "method doesNotExist()");
        assertThat(finding.detail()).containsEntry("location", "class Bad");
    }

    @Test
    void cleanFileCompilesSuccessfullyWithNoFindings() throws IOException {
        Path file = writeSource("Good.java", """
                class Good {
                    int total(int a, int b) {
                        return a + b;
                    }
                }
                """);

        CompileResult result = compiler.compile(new CompileRequest(
                List.of(file), 21, List.of(), List.of(projectRoot), outputDir, projectRoot, false));

        assertThat(result.success()).isTrue();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void seededBigDecimalEqualsIsFoundWhenErrorProneIsEnabled() throws IOException {
        Path file = writeSource("Seeded.java", """
                import java.math.BigDecimal;

                class Seeded {
                    boolean check(BigDecimal a, BigDecimal b) {
                        return a.equals(b);
                    }
                }
                """);

        CompileResult result = compiler.compile(new CompileRequest(
                List.of(file), 21, List.of(), List.of(projectRoot), outputDir, projectRoot, true));

        Finding finding = result.findings().stream().filter(f -> f.ruleId().equals("EP:BigDecimalEquals")).findFirst()
                .orElseThrow(() -> new AssertionError("Expected EP:BigDecimalEquals, got: " + result.findings()));
        assertThat(finding.severity()).isEqualTo(Severity.MAJOR);
        assertThat(finding.engine()).isEqualTo("errorprone");
        assertThat(finding.message()).contains("BigDecimal");
    }

    @Test
    void sameSeedIsNotFoundWhenErrorProneIsDisabled() throws IOException {
        Path file = writeSource("Seeded.java", """
                import java.math.BigDecimal;

                class Seeded {
                    boolean check(BigDecimal a, BigDecimal b) {
                        return a.equals(b);
                    }
                }
                """);

        CompileResult result = compiler.compile(new CompileRequest(
                List.of(file), 21, List.of(), List.of(projectRoot), outputDir, projectRoot, false));

        assertThat(result.success()).isTrue();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void annotationProcessingIsHonoredForALombokGeneratedMethod() throws IOException {
        ModuleInfo module = ProjectDetector.detect(FIXTURES.resolve("maven-spring-jpa-lombok"), Optional.of(BuildTool.MAVEN))
                .orElseThrow();
        Optional<ClasspathCache> cache = new MavenClasspathExtractor().extract(
                module, false, true, List.of(), System.getenv(), WINDOWS);
        assertThat(cache).as("classpath extraction against the real fixture").isPresent();

        Path file = writeSource("UsesLombok.java", """
                import lombok.Data;

                class UsesLombok {
                    @Data
                    static class Holder {
                        private String name;
                    }

                    String read(Holder h) {
                        return h.getName();
                    }
                }
                """);

        CompileResult result = compiler.compile(new CompileRequest(
                List.of(file), 21, cache.get().compileClasspath(), List.of(projectRoot), outputDir, projectRoot, false));

        assertThat(result.success()).as("findings: %s", result.findings()).isTrue();
    }

    @Test
    void neverWritesUnderTargetDirectory() throws IOException {
        Path file = writeSource("Good.java", "class Good { }\n");
        long before = countFilesUnderTarget();

        compiler.compile(new CompileRequest(List.of(file), 21, List.of(), List.of(projectRoot), outputDir, projectRoot, false));

        assertThat(countFilesUnderTarget()).isEqualTo(before);
    }

    private long countFilesUnderTarget() throws IOException {
        Path target = Path.of("target").toAbsolutePath().normalize();
        try (Stream<Path> walk = Files.walk(target)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }
}
