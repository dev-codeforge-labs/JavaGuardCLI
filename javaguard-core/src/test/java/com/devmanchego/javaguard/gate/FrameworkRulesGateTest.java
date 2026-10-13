package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.change.ChangeTracker;
import com.devmanchego.javaguard.change.GitClient;
import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.model.ChangeType;
import com.devmanchego.javaguard.model.FileChange;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.HookEvent;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 11 through the real POST_EDIT gate: the Spring/JPA rules fire on the {@code maven-spring-jpa-lombok}
 * fixture, whose pom declares Spring Boot, Hibernate and Jakarta Persistence, and stay silent on the same code in
 * {@code maven-simple}, which declares none of them (specification &sect;11.2); {@code arch.rules} from the
 * configuration drive JG-ARCH-001 (&sect;11.6).
 */
class FrameworkRulesGateTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures").toAbsolutePath().normalize();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC);

    private static final String PRIVATE_TRANSACTIONAL = """
            package com.acme.service;

            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;

            @Service
            public class OrderService {
                @Transactional
                private void save() {
                }
            }
            """;

    private static final String DATA_ENTITY = """
            package com.acme.entity;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import lombok.Data;

            @Entity
            @Data
            public class Order {
                @Id
                private Long id;
            }
            """;

    @TempDir
    Path home;

    @TempDir
    Path project;

    private void copy(String fixture) throws IOException {
        Path from = FIXTURES.resolve(fixture);
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path target = project.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else if (!("/" + from.relativize(source)).replace('\\', '/').contains("/target/")) {
                    Files.copy(source, target);
                }
            }
        }
    }

    private GateResult write(String relative, String content) throws Exception {
        JavaGuardPaths paths = new JavaGuardPaths(project);
        paths.ensureLayout();
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        JavaGuardConfig config = ConfigLoader.load(ConfigSources.standard(project, Optional.empty(), home, Map.of())).config();
        ChangeTracker tracker = new ChangeTracker(paths, GitClient.none(), CLOCK);
        HookEvent event = new HookEvent(AgentKind.CLAUDE, GateKind.POST_EDIT, "s1", Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath(),
                Optional.empty(), "Write", List.of(FileChange.rewrite(file, ChangeType.ADD)), false, Optional.empty(), CLOCK.instant());
        return new PostEditGate(paths, config, tracker, CLOCK).run(event);
    }

    private static List<String> ruleIds(GateResult result) {
        return Stream.concat(result.blocking().stream(), result.advisory().stream()).map(Finding::ruleId).toList();
    }

    @Test
    void aProxyAnnotationOnAPrivateMethodIsReportedInASpringProject() throws Exception {
        copy("maven-spring-jpa-lombok");

        GateResult result = write("src/main/java/com/acme/service/OrderService.java", PRIVATE_TRANSACTIONAL);

        assertThat(ruleIds(result)).contains("JG-SPR-001");
        Finding f = result.advisory().stream().filter(x -> x.ruleId().equals("JG-SPR-001")).findFirst().orElseThrow();
        assertThat(f.file()).contains(Path.of("src/main/java/com/acme/service/OrderService.java"));
        assertThat(f.message()).contains("@Transactional").contains("private").contains("save()");
    }

    @Test
    void theSameCodeInAProjectWithoutSpringRaisesNoSpringRule() throws Exception {
        copy("maven-simple");

        GateResult result = write("src/main/java/com/acme/service/OrderService.java", PRIVATE_TRANSACTIONAL);

        assertThat(ruleIds(result)).noneMatch(id -> id.startsWith("JG-SPR-"));
    }

    @Test
    void lombokDataOnAnEntityIsReportedWhereJpaIsDetectedAndNotWhereItIsNot() throws Exception {
        copy("maven-spring-jpa-lombok");
        assertThat(ruleIds(write("src/main/java/com/acme/entity/Order.java", DATA_ENTITY))).contains("JG-SPR-008");
    }

    @Test
    void lombokDataOnAnEntityIsSilentWithoutJpa() throws Exception {
        copy("maven-simple");
        assertThat(ruleIds(write("src/main/java/com/acme/entity/Order.java", DATA_ENTITY))).doesNotContain("JG-SPR-008");
    }

    @Test
    void anEntityDeclaredInAnotherFileOfTheModuleIsKnownToTheControllerRule() throws Exception {
        copy("maven-spring-jpa-lombok"); // com/acme/entity/User.java is an @Entity
        String controller = """
                package com.acme.web;

                import com.acme.entity.User;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class UserController {
                    @GetMapping("/users/1")
                    public User one() {
                        return null;
                    }
                }
                """;

        GateResult result = write("src/main/java/com/acme/web/UserController.java", controller);

        assertThat(ruleIds(result)).contains("JG-SPR-010");
    }

    @Test
    void aSpringRuleCanBeSwitchedOffInTheConfiguration() throws Exception {
        copy("maven-spring-jpa-lombok");
        Files.writeString(project.resolve("javaguard.yml"), "rules:\n  JG-SPR-001: { enabled: false }\n");

        GateResult result = write("src/main/java/com/acme/service/OrderService.java", PRIVATE_TRANSACTIONAL);

        assertThat(ruleIds(result)).doesNotContain("JG-SPR-001");
    }

    @Test
    void anArchitectureRuleFromTheConfigurationIsEnforcedOnTheImports() throws Exception {
        copy("maven-simple");
        Files.writeString(project.resolve("javaguard.yml"), """
                arch:
                  rules:
                    - from: "com.acme.domain.."
                      mustNotDependOn: ["com.acme.web.."]
                """);
        String domain = """
                package com.acme.domain;

                import com.acme.web.OrderController;

                public class Order {
                }
                """;

        GateResult result = write("src/main/java/com/acme/domain/Order.java", domain);

        assertThat(result.verdict()).isEqualTo(Verdict.ADVISE);
        assertThat(result.advisory()).extracting(Finding::ruleId).contains("JG-ARCH-001");
        Finding f = result.advisory().stream().filter(x -> x.ruleId().equals("JG-ARCH-001")).findFirst().orElseThrow();
        assertThat(f.line()).isEqualTo(3);
    }

    @Test
    void withoutArchitectureRulesTheSameImportIsFine() throws Exception {
        copy("maven-simple");
        String domain = "package com.acme.domain;\n\nimport com.acme.web.OrderController;\n\npublic class Order {\n}\n";

        assertThat(ruleIds(write("src/main/java/com/acme/domain/Order.java", domain))).doesNotContain("JG-ARCH-001");
    }
}
