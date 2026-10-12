package com.devmanchego.javaguard.analysis.http;

import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.gate.DeepAnalyzer;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.project.ReactorModules;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 25: JG-API-002..004 on a Spring Boot module, baseline against current. */
class HttpContractAnalyzerTest {

    private static final String POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>shop</groupId><artifactId>shop</artifactId><version>1</version>
              <dependencies>
                <dependency>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-web</artifactId>
                  <version>3.3.4</version>
                </dependency>
              </dependencies>
            </project>
            """;
    private static final String CONTROLLER = "src/main/java/shop/OrderController.java";
    private static final String DTO = "src/main/java/shop/OrderDto.java";
    private static final String ORDERS = """
            package shop;

            import org.springframework.web.bind.annotation.*;

            @RestController
            @RequestMapping("/orders")
            class OrderController {
                @GetMapping("/{id}")
                OrderDto get(@PathVariable long id) {
                    return null;
                }

                @GetMapping
                java.util.List<OrderDto> list(@RequestParam(required = false) String status) {
                    return null;
                }

                @PostMapping(consumes = "application/json")
                OrderDto create(@RequestBody OrderDto order) {
                    return order;
                }

                @DeleteMapping("/{id}")
                void delete(@PathVariable long id) {
                }
            }
            """;

    @TempDir
    Path root;

    @TempDir
    Path home;

    private final Map<String, String> baselines = new HashMap<>();

    @BeforeEach
    void project() throws IOException {
        Files.writeString(root.resolve("pom.xml"), POM);
    }

    private void change(String relative, String before, String after) throws IOException {
        baselines.put(relative, before);
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        if (after == null) {
            Files.deleteIfExists(file);
        } else {
            Files.writeString(file, after);
        }
    }

    private List<Finding> analyze() throws Exception {
        var config = ConfigLoader.load(ConfigSources.standard(root, Optional.empty(), home, Map.of())).config();
        List<Path> files = baselines.keySet().stream().map(root::resolve).toList();
        DeepAnalyzer.Context context = new DeepAnalyzer.Context(files, ReactorModules.load(root), Duration.ofMinutes(1),
                file -> Optional.ofNullable(baselines.get(root.relativize(file).toString().replace('\\', '/'))).filter(s -> !s.isEmpty()),
                config, new JavaGuardPaths(root));
        return new HttpContractAnalyzer().analyze(context);
    }

    // --- JG-API-002 ---------------------------------------------------------------------------------------

    @Test
    void aRemovedEndpointIsReported() throws Exception {
        change(CONTROLLER, ORDERS, ORDERS.replace("    @DeleteMapping(\"/{id}\")\n    void delete(@PathVariable long id) {\n    }\n", ""));

        List<Finding> findings = analyze();

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("JG-API-002");
            assertThat(f.message()).contains("DELETE /orders/{id}").contains("404");
        });
    }

    @Test
    void renamingTheHandlerMovingThePathToTheClassAndRenamingPathVariablesChangeNothing() throws Exception {
        String after = ORDERS.replace("@RequestMapping(\"/orders\")", "@RequestMapping(\"/orders/\")")
                .replace("OrderDto get(@PathVariable long id)", "OrderDto findOne(@PathVariable(\"orderId\") long id)")
                .replace("@GetMapping(\"/{id}\")", "@GetMapping(\"{orderId:\\\\d+}\")")
                .replace("@DeleteMapping(\"/{id}\")", "@DeleteMapping(path = \"/{orderId}/\")");

        assertThat(analyze(after)).isEmpty();
    }

    private List<Finding> analyze(String after) throws Exception {
        change(CONTROLLER, ORDERS, after);
        return analyze();
    }

    @Test
    void aMovedPathNamesWhereTheHandlerNowAnswers() throws Exception {
        List<Finding> findings = analyze(ORDERS.replace("@DeleteMapping(\"/{id}\")", "@DeleteMapping(\"/{id}/cancel\")"));

        assertThat(findings).singleElement().satisfies(f -> assertThat(f.message())
                .contains("DELETE /orders/{id} no longer exists").contains("now answers DELETE /orders/{id}/cancel"));
    }

    @Test
    void anEndpointMovedToAnotherChangedControllerIsNotReported() throws Exception {
        change(CONTROLLER, ORDERS, ORDERS.replace("    @DeleteMapping(\"/{id}\")\n    void delete(@PathVariable long id) {\n    }\n", ""));
        change("src/main/java/shop/AdminController.java", "", """
                package shop;

                import org.springframework.web.bind.annotation.*;

                @RestController
                class AdminController {
                    @DeleteMapping("/orders/{orderId}")
                    void remove(@PathVariable long orderId) {
                    }
                }
                """);

        assertThat(analyze()).isEmpty();
    }

    @Test
    void droppingADeclaredMediaTypeIsReported() throws Exception {
        List<Finding> findings = analyze(ORDERS.replace("consumes = \"application/json\"", "consumes = \"application/xml\""));

        assertThat(findings).singleElement().satisfies(f -> assertThat(f.message()).contains("no longer consumes [application/json]"));
    }

    @Test
    void aDeletedControllerReportsEachOfItsEndpoints() throws Exception {
        change(CONTROLLER, ORDERS, null);

        assertThat(analyze()).extracting(Finding::ruleId).containsOnly("JG-API-002").hasSize(4);
    }

    // --- JG-API-003 ---------------------------------------------------------------------------------------

    @Test
    void aNewRequiredParameterIsReportedAndAnOptionalOneIsNot() throws Exception {
        String after = ORDERS.replace("@RequestParam(required = false) String status)",
                        "@RequestParam(required = false) String status, @RequestParam String tenant, "
                                + "@RequestParam(defaultValue = \"10\") int size, @RequestHeader(\"X-Trace\") Optional<String> trace)")
                .replace("void delete(@PathVariable long id)", "void delete(@PathVariable long id, @RequestHeader(\"X-Reason\") String reason)");

        List<Finding> findings = analyze(after);

        assertThat(findings).extracting(Finding::ruleId).containsOnly("JG-API-003");
        assertThat(findings).extracting(Finding::message).anySatisfy(m -> assertThat(m).contains("query parameter 'tenant'"))
                .anySatisfy(m -> assertThat(m).contains("header 'X-Reason'")).hasSize(2);
    }

    @Test
    void aParameterThatBecomesRequiredIsReported() throws Exception {
        List<Finding> findings = analyze(ORDERS.replace("@RequestParam(required = false) String status", "@RequestParam String status"));

        assertThat(findings).singleElement().satisfies(f -> assertThat(f.message()).contains("'status'"));
    }

    @Test
    void aNewRequiredBodyIsReported() throws Exception {
        List<Finding> findings = analyze(ORDERS.replace("void delete(@PathVariable long id)",
                "void delete(@PathVariable long id, @RequestBody OrderDto reason)"));

        assertThat(findings).singleElement().satisfies(f -> assertThat(f.message()).contains("now requires a request body"));
    }

    // --- JG-API-004 ---------------------------------------------------------------------------------------

    @Test
    void aRecordComponentRenamedIsReportedWithTheLikelyNewName() throws Exception {
        Files.createDirectories(root.resolve("src/main/java/shop"));
        Files.writeString(root.resolve(CONTROLLER), ORDERS);
        change(DTO, "package shop;\nrecord OrderDto(long id, String customerName, int total) {\n}\n",
                "package shop;\nrecord OrderDto(long id, String customer, int total) {\n}\n");

        List<Finding> findings = analyze();

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("JG-API-004");
            assertThat(f.message()).contains("'customerName'").contains("renamed to 'customer'");
            assertThat(f.remediation()).contains("@JsonProperty(\"customerName\")");
        });
    }

    @Test
    void keepingTheJsonNameWithJsonPropertyIsNotAChange() throws Exception {
        Files.createDirectories(root.resolve("src/main/java/shop"));
        Files.writeString(root.resolve(CONTROLLER), ORDERS);
        change(DTO, "package shop;\nrecord OrderDto(long id, String customerName) {\n}\n",
                "package shop;\nimport com.fasterxml.jackson.annotation.JsonProperty;\n"
                        + "record OrderDto(long id, @JsonProperty(\"customerName\") String customer) {\n}\n");

        assertThat(analyze()).isEmpty();
    }

    @Test
    void classesFollowJacksonVisibilityLombokAndJsonIgnore() throws Exception {
        Files.createDirectories(root.resolve("src/main/java/shop"));
        Files.writeString(root.resolve(CONTROLLER), ORDERS);
        String before = """
                package shop;
                @lombok.Data
                class OrderDto {
                    private long id;
                    private String note;
                    private String internal;
                    public String getURL() { return ""; }
                }
                """;
        String after = """
                package shop;
                @lombok.Data
                class OrderDto {
                    private long id;
                    @com.fasterxml.jackson.annotation.JsonIgnore
                    private String note;
                    private transient String internal;
                    public String getUrl() { return ""; }
                }
                """;
        change(DTO, before, after);

        List<Finding> findings = analyze();

        assertThat(findings).extracting(Finding::message).hasSize(2)
                .anySatisfy(m -> assertThat(m).contains("'note'"))
                .anySatisfy(m -> assertThat(m).contains("'internal'"));
    }

    @Test
    void aTypeNoEndpointUsesIsNotAContract() throws Exception {
        Files.createDirectories(root.resolve("src/main/java/shop"));
        Files.writeString(root.resolve(CONTROLLER), ORDERS);
        change("src/main/java/shop/Internal.java", "package shop;\nrecord Internal(String a) {\n}\n",
                "package shop;\nrecord Internal(String b) {\n}\n");

        assertThat(analyze()).isEmpty();
    }

    @Test
    void httpPathNormalization() {
        assertThat(HttpContracts.normalize("/a/")).isEqualTo(HttpContracts.normalize("/a"));
        assertThat(HttpContracts.normalize("/orders/{id}")).isEqualTo(HttpContracts.normalize("/orders/{orderId:\\d+}"));
        assertThat(HttpContracts.combine("/orders/", "/{id}")).isEqualTo("/orders/{id}");
        assertThat(HttpContracts.combine("orders", "")).isEqualTo("/orders");
        assertThat(HttpContracts.combine("", "")).isEqualTo("/");
        assertThat(HttpContracts.normalize("/files/{*path}")).isEqualTo("/files/{}");
    }
}
