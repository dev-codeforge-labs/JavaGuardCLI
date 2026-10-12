package com.devmanchego.javaguard.analysis.deps;

import com.devmanchego.javaguard.analysis.deps.OsvClient.Vulnerability;
import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.gate.DeepAnalyzer;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.ReactorModules;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan Phase 18: the OSV client against responses recorded from api.osv.dev (2026-10-06) and served by a local HTTP server,
 * the CVSS arithmetic, and {@link OsvAnalyzer} with the client at its boundary.
 */
class OsvTest {

    private static final Dependency LOG4J = new Dependency("org.apache.logging.log4j", "log4j-core", "2.14.1", "");
    private static final Dependency SLF4J = new Dependency("org.slf4j", "slf4j-api", "2.0.13", "");

    @TempDir
    Path root;

    private static String resource(String name) throws IOException {
        try (InputStream in = OsvTest.class.getResourceAsStream("/osv/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // --- CVSS ------------------------------------------------------------------------------------

    @Test
    void cvssBaseScoresMatchTheSpecificationExamples() {
        assertThat(CvssV3.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H").getAsDouble()).isEqualTo(10.0);
        assertThat(CvssV3.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H/E:H").getAsDouble()).isEqualTo(10.0);
        assertThat(CvssV3.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H").getAsDouble()).isEqualTo(9.8);
        assertThat(CvssV3.baseScore("CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:C/C:H/I:H/A:H").getAsDouble()).isEqualTo(9.0);
        assertThat(CvssV3.baseScore("CVSS:3.1/AV:N/AC:H/PR:H/UI:N/S:U/C:H/I:H/A:H").getAsDouble()).isEqualTo(6.6);
        assertThat(CvssV3.baseScore("CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N").getAsDouble()).isEqualTo(5.5);
        assertThat(CvssV3.baseScore("CVSS:3.0/AV:N/AC:L/PR:N/UI:R/S:C/C:L/I:L/A:N").getAsDouble()).isEqualTo(6.1);
        assertThat(CvssV3.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:N").getAsDouble()).isZero();
    }

    @Test
    void anythingButACompleteV3VectorHasNoScore() {
        assertThat(CvssV3.baseScore("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N")).isEmpty();
        assertThat(CvssV3.baseScore("CVSS:3.1/AV:N/AC:L")).isEmpty();
        assertThat(CvssV3.baseScore("garbage")).isEmpty();
        assertThat(CvssV3.baseScore(null)).isEmpty();
    }

    // --- HTTP client against recorded responses ---------------------------------------------------

    /** Serves the recorded answers; records what was asked. */
    private static final class Recorded implements AutoCloseable {
        final HttpServer server;
        final List<String> requests = new CopyOnWriteArrayList<>();

        Recorded(int delayMillis, int status) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/querybatch", exchange -> {
                requests.add("POST /v1/querybatch " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, resource("querybatch-response.json"), delayMillis, status);
            });
            server.createContext("/v1/vulns/", exchange -> {
                String id = exchange.getRequestURI().getPath().substring("/v1/vulns/".length());
                requests.add("GET " + exchange.getRequestURI().getPath());
                Path file = Path.of("src/test/resources/osv/vuln-" + id + ".json");
                if (Files.exists(file)) {
                    respond(exchange, Files.readString(file), delayMillis, 200);
                } else {
                    respond(exchange, "{\"id\":\"" + id + "\"}", delayMillis, 200); // advisories not recorded: no severity known
                }
            });
            server.start();
        }

        private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body, int delayMillis, int status) throws IOException {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @Test
    void theClientAsksForCoordinatesOnlyAndReadsSeveritiesFromTheVectors() throws Exception {
        try (Recorded osv = new Recorded(0, 200)) {
            List<Vulnerability> found = new HttpOsvClient(osv.uri(), Duration.ofSeconds(5)).query(List.of(LOG4J, SLF4J));

            assertThat(osv.requests.get(0)).startsWith("POST /v1/querybatch").contains("\"org.apache.logging.log4j:log4j-core\"")
                    .contains("\"ecosystem\":\"Maven\"").contains("\"version\":\"2.14.1\"").doesNotContain("src/").doesNotContain(root.toString());
            assertThat(found).extracting(v -> v.dependency().key()).containsOnly("org.apache.logging.log4j:log4j-core");
            assertThat(found).hasSize(7);
            Vulnerability shell = found.stream().filter(v -> v.id().equals("GHSA-jfh8-c2jp-5v3q")).findFirst().orElseThrow();
            assertThat(shell.summary()).isEqualTo("Remote code injection in Log4j");
            assertThat(shell.score()).hasValue(10.0);
            assertThat(found.stream().filter(v -> v.id().equals("GHSA-8489-44mv-ggj8")).findFirst().orElseThrow().score()).hasValue(6.6);
            assertThat(found.stream().filter(v -> v.id().equals("GHSA-7rjr-3q55-vv33")).findFirst().orElseThrow().score()).hasValue(9.0);
            assertThat(found.stream().filter(v -> v.id().equals("GHSA-3pxv-7cmr-fjr4")).findFirst().orElseThrow().score()).isEmpty();
        }
    }

    @Test
    void aServerErrorOrASlowServerIsAnIoException() throws Exception {
        try (Recorded broken = new Recorded(0, 500)) {
            assertThatThrownBy(() -> new HttpOsvClient(broken.uri(), Duration.ofSeconds(5)).query(List.of(LOG4J)))
                    .isInstanceOf(IOException.class).hasMessageContaining("500");
        }
        try (Recorded slow = new Recorded(3000, 200)) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> new HttpOsvClient(slow.uri(), Duration.ofMillis(500)).query(List.of(LOG4J))).isInstanceOf(IOException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
        }
        assertThatThrownBy(() -> new HttpOsvClient(URI.create("http://127.0.0.1:1"), Duration.ofSeconds(2)).query(List.of(LOG4J)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void nothingToAskMeansNoRequest() throws Exception {
        assertThat(new HttpOsvClient(URI.create("http://127.0.0.1:1"), Duration.ofSeconds(1)).query(List.of())).isEmpty();
    }

    @Test
    void theAdvisoryDatabasesOwnLabelIsTheFallbackWhenThereIsNoVectorOfV3() throws Exception {
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"severity\":[{\"type\":\"CVSS_V4\",\"score\":\"CVSS:4.0/AV:N\"}],\"database_specific\":{\"severity\":\"HIGH\"}}");

        assertThat(HttpOsvClient.score(node)).hasValue(8.0);
        assertThat(HttpOsvClient.score(new com.fasterxml.jackson.databind.ObjectMapper().readTree("{}"))).isEmpty();
    }

    // --- the analyzer ------------------------------------------------------------------------------

    private static String pom(String... dependencies) {
        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <modelVersion>4.0.0</modelVersion>\n  <dependencies>\n"
                + String.join("\n", dependencies) + "\n  </dependencies>\n</project>\n";
    }

    private static String dep(Dependency d) {
        return "    <dependency><groupId>" + d.groupId() + "</groupId><artifactId>" + d.artifactId() + "</artifactId><version>"
                + d.version() + "</version></dependency>";
    }

    private DeepAnalyzer.Context context(String baseline, String current) throws Exception {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, current);
        JavaGuardPaths paths = new JavaGuardPaths(root);
        JavaGuardConfig config = ConfigLoader.load(ConfigSources.standard(root, Optional.empty(), root.resolve("home"), Map.of())).config();
        return new DeepAnalyzer.Context(List.of(pom), ReactorModules.load(root), Duration.ofMinutes(5),
                file -> baseline == null ? Optional.empty() : Optional.of(baseline), config, paths);
    }

    private static OsvClient fixed(List<Vulnerability> vulnerabilities, List<List<Dependency>> asked) {
        return dependencies -> {
            asked.add(new ArrayList<>(dependencies));
            return vulnerabilities;
        };
    }

    @Test
    void vulnerabilitiesOfAnAddedDependencyAreFindingsWithSeverityFromTheScore() throws Exception {
        List<List<Dependency>> asked = new ArrayList<>();
        OsvClient client = fixed(List.of(
                new Vulnerability(LOG4J, "GHSA-A", "Remote code injection", OptionalDouble.of(10.0)),
                new Vulnerability(LOG4J, "GHSA-B", "Denial of service", OptionalDouble.of(7.5)),
                new Vulnerability(LOG4J, "GHSA-C", "Information leak", OptionalDouble.of(5.3)),
                new Vulnerability(LOG4J, "GHSA-D", "Unscored", OptionalDouble.empty())), asked);

        List<Finding> findings = new OsvAnalyzer(timeout -> client).analyze(context(pom(dep(SLF4J)), pom(dep(SLF4J), dep(LOG4J))));

        assertThat(asked).singleElement().isEqualTo(List.of(LOG4J)); // only what the session added
        assertThat(findings).extracting(Finding::severity).containsExactly(Severity.CRITICAL, Severity.MAJOR, Severity.INFO, Severity.INFO);
        assertThat(findings.get(0).ruleId()).isEqualTo("JG-DEP-002");
        assertThat(findings.get(0).category()).isEqualTo(Category.DEPENDENCY);
        assertThat(findings.get(0).message()).contains("org.apache.logging.log4j:log4j-core:2.14.1").contains("GHSA-A").contains("CVSS 10.0");
        assertThat(findings.get(0).file()).contains(Path.of("pom.xml"));
        assertThat(findings.get(0).line()).isEqualTo(5);
        assertThat(findings.get(0).remediation()).contains("https://osv.dev/vulnerability/GHSA-A");
    }

    @Test
    void aChangedVersionIsCheckedAnUnchangedOneIsNot() throws Exception {
        List<List<Dependency>> asked = new ArrayList<>();
        Dependency older = new Dependency(LOG4J.groupId(), LOG4J.artifactId(), "2.17.2", "");

        new OsvAnalyzer(timeout -> fixed(List.of(), asked)).analyze(context(pom(dep(older)), pom(dep(LOG4J))));
        new OsvAnalyzer(timeout -> fixed(List.of(), asked)).analyze(context(pom(dep(LOG4J)), pom(dep(LOG4J))));

        assertThat(asked).singleElement().isEqualTo(List.of(LOG4J));
    }

    @Test
    void versionsLeftToABomOrADynamicMarkerAreNotAskedAbout() throws Exception {
        List<List<Dependency>> asked = new ArrayList<>();
        String current = pom("    <dependency><groupId>g</groupId><artifactId>managed</artifactId></dependency>",
                "    <dependency><groupId>g</groupId><artifactId>range</artifactId><version>[1.0,2.0)</version></dependency>",
                "    <dependency><groupId>g</groupId><artifactId>prop</artifactId><version>${unknown}</version></dependency>");

        List<Finding> findings = new OsvAnalyzer(timeout -> fixed(List.of(), asked)).analyze(context(null, current));

        assertThat(asked).isEmpty();
        assertThat(findings).isEmpty();
    }

    @Test
    void aFailedLookupIsOneInfrastructureFindingNotABlock() throws Exception {
        OsvClient down = dependencies -> {
            throw new IOException("Network is unreachable");
        };

        List<Finding> findings = new OsvAnalyzer(timeout -> down).analyze(context(null, pom(dep(LOG4J))));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.category()).isEqualTo(Category.INFRASTRUCTURE);
            assertThat(f.severity()).isEqualTo(Severity.INFO);
            assertThat(f.message()).contains("Network is unreachable").contains("not checked");
        });
    }

    @Test
    void disabledByConfigAndGradleFilesAreHonored() throws Exception {
        Files.writeString(root.resolve("javaguard.yml"), "deps:\n  osv:\n    enabled: false\n");
        List<List<Dependency>> asked = new ArrayList<>();
        assertThat(new OsvAnalyzer(timeout -> fixed(List.of(), asked)).analyze(context(null, pom(dep(LOG4J))))).isEmpty();
        assertThat(asked).isEmpty();

        Files.delete(root.resolve("javaguard.yml"));
        Path gradle = Files.writeString(root.resolve("build.gradle"), "dependencies { implementation 'org.apache.logging.log4j:log4j-core:2.14.1' }\n");
        JavaGuardPaths paths = new JavaGuardPaths(root);
        JavaGuardConfig config = ConfigLoader.load(ConfigSources.standard(root, Optional.empty(), root.resolve("home"), Map.of())).config();
        DeepAnalyzer.Context context = new DeepAnalyzer.Context(List.of(gradle), ReactorModules.load(root), Duration.ofMinutes(5),
                file -> Optional.empty(), config, paths);

        new OsvAnalyzer(timeout -> fixed(List.of(), asked)).analyze(context);

        assertThat(asked).singleElement().satisfies(list -> assertThat(list).extracting(Dependency::coordinates)
                .containsExactly("org.apache.logging.log4j:log4j-core:2.14.1"));
    }

    @Test
    void atMostFiveAdvisoriesPerDependencyWorstFirst() throws Exception {
        List<Vulnerability> many = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            many.add(new Vulnerability(LOG4J, "GHSA-" + i, "s" + i, OptionalDouble.of(i)));
        }

        List<Finding> findings = new OsvAnalyzer(timeout -> fixed(many, new ArrayList<>())).analyze(context(null, pom(dep(LOG4J))));

        assertThat(findings).hasSize(5);
        assertThat(findings.get(0).detail()).containsEntry("advisory", "GHSA-8");
        assertThat(findings.get(0).message()).contains("3 more advisories");
    }
}
