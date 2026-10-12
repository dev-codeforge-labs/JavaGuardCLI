package oracle;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates every generated case repository the way an application context does at start-up (JpaRepositoryFactoryBean
 * delegates to JpaRepositoryFactory.getRepository, which creates every query method eagerly), and records whether it
 * could. One line per case: name|VALID or name|INVALID|first line of the root cause.
 */
class OracleTest {

    @Test
    void createEveryCaseRepository() throws Exception {
        List<String> names = Files.readAllLines(Path.of("cases.index"), StandardCharsets.UTF_8);
        List<String> results = new ArrayList<>();
        try (EntityManagerFactory factory = Persistence.createEntityManagerFactory("oracle")) {
            EntityManager entityManager = factory.createEntityManager();
            JpaRepositoryFactory repositories = new JpaRepositoryFactory(entityManager);
            for (String name : names) {
                if (name.isBlank()) {
                    continue;
                }
                try {
                    repositories.getRepository(Class.forName("oracle.cases." + name.trim()));
                    results.add(name.trim() + "|VALID");
                } catch (Throwable e) {
                    Throwable root = e;
                    while (root.getCause() != null && root.getCause() != root) {
                        root = root.getCause();
                    }
                    String message = String.valueOf(root.getMessage()).lines().findFirst().orElse("").replace('|', '/');
                    results.add(name.trim() + "|INVALID|" + root.getClass().getSimpleName() + ": " + message);
                }
            }
        }
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/oracle-results.txt"), results, StandardCharsets.UTF_8);
    }
}
