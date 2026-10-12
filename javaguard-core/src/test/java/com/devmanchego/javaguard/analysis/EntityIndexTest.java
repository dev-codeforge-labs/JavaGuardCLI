package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.project.Framework;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 11: entities declared in other files of the module (JG-SPR-003 lazy-collection variant, JG-SPR-010). */
class EntityIndexTest {

    @TempDir
    Path root;

    private Path write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private AstCheckEngine engine(EntityIndex index) {
        return new AstCheckEngine(EnumSet.of(Framework.SPRING_BOOT, Framework.HIBERNATE), index, List.of());
    }

    private static List<String> ruleIds(List<Finding> findings) {
        return findings.stream().map(Finding::ruleId).toList();
    }

    @Test
    void entitiesAndTheirToManyGettersAreFoundAcrossFiles() throws IOException {
        write("com/acme/Customer.java", """
                package com.acme;
                import jakarta.persistence.*;
                import java.util.List;
                @Entity
                public class Customer {
                    @OneToMany private List<Object> orders;
                    @ManyToMany private List<Object> tags;
                    @ManyToOne private Object region;
                    private String name;
                }
                """);
        write("com/acme/Plain.java", "package com.acme;\npublic class Plain {}\n");

        EntityIndex index = EntityIndex.scanning(List.of(root));

        assertThat(index.isEntity("Customer")).isTrue();
        assertThat(index.isEntity("Plain")).isFalse();
        assertThat(index.toManyGetters("Customer")).containsExactlyInAnyOrder("getOrders", "getTags");
        assertThat(index.toManyGetters("Nobody")).isEmpty();
    }

    @Test
    void aControllerReturningAnEntityDeclaredInAnotherFileIsFound() throws IOException {
        write("com/acme/Invoice.java", "package com.acme;\n@jakarta.persistence.Entity\npublic class Invoice {}\n");
        String controller = """
                package com.acme;
                import org.springframework.web.bind.annotation.*;
                @RestController
                class InvoiceController {
                    @GetMapping("/invoices/1")
                    public Invoice one() { return null; }
                }
                """;

        List<Finding> withIndex = engine(EntityIndex.scanning(List.of(root))).analyze("src/main/java/InvoiceController.java", controller);
        List<Finding> withoutIndex = engine(EntityIndex.empty()).analyze("src/main/java/InvoiceController.java", controller);

        assertThat(ruleIds(withIndex)).contains("JG-SPR-010");
        assertThat(ruleIds(withoutIndex)).as("without the index the other file's entity is unknown").doesNotContain("JG-SPR-010");
    }

    @Test
    void aLazyCollectionGetterOnAnEntityFromAnotherFileIsReportedAsInfoInALoop() throws IOException {
        write("com/acme/Customer.java", """
                package com.acme;
                @jakarta.persistence.Entity
                public class Customer {
                    @jakarta.persistence.OneToMany private java.util.List<Object> orders;
                }
                """);
        String loop = """
                package com.acme;
                import java.util.List;
                class Report {
                    int count(List<Customer> customers) {
                        int total = 0;
                        for (Customer c : customers) {
                            total += c.getOrders().size();
                        }
                        return total;
                    }
                }
                """;

        List<Finding> findings = engine(EntityIndex.scanning(List.of(root))).analyze("src/main/java/Report.java", loop);

        assertThat(findings).filteredOn(f -> f.ruleId().equals("JG-SPR-003")).singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo(com.devmanchego.javaguard.model.Severity.INFO));
    }

    @Test
    void aQueryInTheIterableOfAForEachIsNotInTheLoop() {
        String source = """
                import java.util.List;
                class Once {
                    private final UserRepository userRepository;
                    Once(UserRepository r) { this.userRepository = r; }
                    void run() {
                        for (Object u : userRepository.findAll()) {
                            u.hashCode();
                        }
                    }
                }
                interface UserRepository { List<Object> findAll(); }
                """;

        assertThat(ruleIds(engine(EntityIndex.empty()).analyze("src/main/java/Once.java", source))).doesNotContain("JG-SPR-003");
    }

    @Test
    void theIndexIsBuiltOnlyWhenARuleAsksAndOnlyOnce() throws IOException {
        Path file = write("com/acme/Late.java", "package com.acme;\n@jakarta.persistence.Entity\npublic class Late {}\n");
        EntityIndex index = EntityIndex.scanning(List.of(root));
        Files.delete(file); // after construction, before first use

        assertThat(index.isEntity("Late")).as("scanned at first use, so the deleted file is gone").isFalse();
        write("com/acme/Later.java", "package com.acme;\n@jakarta.persistence.Entity\npublic class Later {}\n");
        assertThat(index.isEntity("Later")).as("never rescanned").isFalse();
    }

    @Test
    void aMissingSourceRootIsAnEmptyIndex() {
        assertThat(EntityIndex.scanning(List.of(root.resolve("nope"))).isEntity("X")).isFalse();
    }
}
