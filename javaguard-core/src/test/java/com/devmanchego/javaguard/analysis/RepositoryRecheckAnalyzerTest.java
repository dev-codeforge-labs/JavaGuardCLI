package com.devmanchego.javaguard.analysis;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 22: an entity change re-checks the repositories and queries the turn did not touch. */
class RepositoryRecheckAnalyzerTest {

    private static final String POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>shop</groupId><artifactId>shop</artifactId><version>1</version>
              <dependencies>
                <dependency>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-data-jpa</artifactId>
                  <version>3.3.4</version>
                </dependency>
              </dependencies>
            </project>
            """;
    private static final String CUSTOMER = """
            package shop;

            import jakarta.persistence.*;

            @Entity
            public class Customer {
                @Id
                Long id;
                String firstName;
                String lastName;
            }
            """;
    private static final String REPOSITORY = """
            package shop;

            import java.util.List;
            import org.springframework.data.jpa.repository.JpaRepository;
            import org.springframework.data.jpa.repository.Query;

            public interface CustomerRepository extends JpaRepository<Customer, Long> {
                List<Customer> findByFirstName(String firstName);

                List<Customer> findByNickname(String nickname);

                @Query("select c from Customer c where c.lastName = ?1")
                List<Customer> byLastName(String lastName);
            }
            """;
    private static final String CUSTOMER_PATH = "src/main/java/shop/Customer.java";

    @TempDir
    Path root;

    @TempDir
    Path home;

    @BeforeEach
    void project() throws IOException {
        Files.createDirectories(root.resolve("src/main/java/shop"));
        Files.writeString(root.resolve("pom.xml"), POM);
        Files.writeString(root.resolve(CUSTOMER_PATH), CUSTOMER);
        Files.writeString(root.resolve("src/main/java/shop/CustomerRepository.java"), REPOSITORY);
    }

    private List<Finding> analyzeAfterChangingCustomer(String baseline) throws Exception {
        JavaGuardPaths paths = new JavaGuardPaths(root);
        var config = ConfigLoader.load(ConfigSources.standard(root, Optional.empty(), home, Map.of())).config();
        DeepAnalyzer.Context context = new DeepAnalyzer.Context(List.of(root.resolve(CUSTOMER_PATH)), ReactorModules.load(root),
                Duration.ofMinutes(1), file -> file.endsWith("Customer.java") ? Optional.ofNullable(baseline) : Optional.empty(),
                config, paths);
        return new RepositoryRecheckAnalyzer().analyze(context);
    }

    @Test
    void renamingAnAttributeReportsTheQueriesItBrokeAndNotTheOnesAlreadyBroken() throws Exception {
        Files.writeString(root.resolve(CUSTOMER_PATH), CUSTOMER.replace("firstName", "givenName").replace("lastName", "surname"));

        List<Finding> findings = analyzeAfterChangingCustomer(CUSTOMER);

        assertThat(findings).extracting(Finding::ruleId).containsExactlyInAnyOrder("JG-SPR-015", "JG-SPR-016");
        assertThat(findings).allSatisfy(f -> {
            assertThat(f.file()).contains(Path.of("src/main/java/shop/CustomerRepository.java"));
            assertThat(f.message()).contains("the change to Customer.java broke it");
        });
        assertThat(findings).filteredOn(f -> f.ruleId().equals("JG-SPR-015")).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("findByFirstName").doesNotContain("findByNickname"));
    }

    @Test
    void anEntityChangeThatKeepsTheAttributesReportsNothing() throws Exception {
        Files.writeString(root.resolve(CUSTOMER_PATH), CUSTOMER.replace("String lastName;", "String lastName;\n    String email;"));

        assertThat(analyzeAfterChangingCustomer(CUSTOMER)).isEmpty();
    }

    @Test
    void aNewEntityFileMakesItsExistingRepositoryCheckable() throws Exception {
        List<Finding> findings = analyzeAfterChangingCustomer(null);

        assertThat(findings).as("before the turn the entity did not exist, so the repository could not be judged")
                .singleElement().satisfies(f -> assertThat(f.message()).contains("findByNickname"));
    }

    @Test
    void modulesWithoutSpringOrJpaAreNotRechecked() throws Exception {
        Files.writeString(root.resolve("pom.xml"), POM.replace("org.springframework.boot", "org.example").replace("spring-boot-starter-data-jpa", "x"));
        Files.writeString(root.resolve(CUSTOMER_PATH), CUSTOMER.replace("firstName", "givenName"));

        assertThat(analyzeAfterChangingCustomer(CUSTOMER)).isEmpty();
    }
}
