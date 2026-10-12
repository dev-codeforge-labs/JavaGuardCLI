package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 22: what the sample harness does not say - severities, messages, lines, and the model read from other files. */
class SpringJpaCorrectnessRulesTest {

    private static final Set<Framework> BOOT_3 = EnumSet.of(Framework.SPRING_BOOT, Framework.SPRING_DATA_JPA, Framework.HIBERNATE,
            Framework.JAKARTA_EE);

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

    @TempDir
    Path module;

    private static List<Finding> analyze(String source, EntityIndex index) {
        return new AstCheckEngine(BOOT_3, index, List.of()).analyze("src/main/java/shop/A.java", source);
    }

    private static List<Finding> only(List<Finding> findings, String ruleId) {
        return findings.stream().filter(f -> f.ruleId().equals(ruleId)).toList();
    }

    private EntityIndex moduleWithCustomer() throws IOException {
        Path sources = module.resolve("src/main/java");
        Files.createDirectories(sources.resolve("shop"));
        Files.writeString(sources.resolve("shop/Customer.java"), CUSTOMER);
        return EntityIndex.scanning(List.of(sources));
    }

    @Test
    void aDerivedQueryOnAnEntityOfAnotherFileIsABlockerThatSuggestsTheRightName() throws IOException {
        String repository = """
                package shop;

                interface CustomerRepository extends org.springframework.data.jpa.repository.JpaRepository<Customer, Long> {
                    java.util.List<Customer> findByFirstname(String name);
                }
                """;

        List<Finding> findings = only(analyze(repository, moduleWithCustomer()), "JG-SPR-015");

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.BLOCKER);
            assertThat(f.line()).isEqualTo(4);
            assertThat(f.message()).contains("findByFirstname").contains("'firstname'").contains("did you mean firstName?");
            assertThat(f.remediation()).contains("firstName").contains("lastName");
        });
    }

    @Test
    void withoutTheEntityInTheModuleTheRepositoryIsNotJudged() {
        String repository = "interface CustomerRepository extends JpaRepository<Customer, Long> {\n"
                + "    java.util.List<Customer> findByFirstname(String name);\n}\n";

        assertThat(only(analyze(repository, EntityIndex.empty()), "JG-SPR-015")).isEmpty();
    }

    @Test
    void tooFewParametersAreReportedWithTheCount() throws IOException {
        String repository = "package shop;\ninterface R extends org.springframework.data.repository.CrudRepository<Customer, Long> {\n"
                + "    java.util.List<Customer> findByFirstNameAndLastName(String first, org.springframework.data.domain.Pageable page);\n}\n";

        List<Finding> findings = only(analyze(repository, moduleWithCustomer()), "JG-SPR-015");

        assertThat(findings).singleElement().satisfies(f -> assertThat(f.message()).contains("has 1 parameter(s)").contains("need 2"));
    }

    @Test
    void jpqlUsingTheClassNameOfARenamedEntityNamesTheEntityName() {
        String source = """
                import jakarta.persistence.*;
                @Entity(name = "Purchase")
                class PurchaseOrder { @Id Long id; java.math.BigDecimal total; }
                interface R extends JpaRepository<PurchaseOrder, Long> {
                    @Query("select p from PurchaseOrder p where p.total > ?1")
                    java.util.List<PurchaseOrder> big(java.math.BigDecimal total);
                }
                """;

        List<Finding> findings = only(analyze(source, EntityIndex.empty()), "JG-SPR-016");

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(f.line()).isEqualTo(5);
            assertThat(f.message()).contains("@Entity(name = \"Purchase\")").contains("JPQL must use Purchase");
        });
    }

    @Test
    void jpqlAttributeTyposAreReportedAgainstTheModuleModel() throws IOException {
        String source = "package shop;\ninterface R extends JpaRepository<Customer, Long> {\n"
                + "    @Query(\"select c from Customer c where c.lastname = :n\")\n    java.util.List<Customer> byName(String n);\n}\n";

        List<Finding> findings = only(analyze(source, moduleWithCustomer()), "JG-SPR-016");

        assertThat(findings).singleElement().satisfies(f -> assertThat(f.message())
                .contains("Customer has no attribute 'lastname'").contains("did you mean lastName?"));
    }

    @Test
    void aCheckedExceptionWithoutRollbackNamesTheTypeAndTheFix() {
        String source = "import org.springframework.transaction.annotation.Transactional;\nclass S {\n    @Transactional\n"
                + "    public void m() throws java.io.IOException {\n    }\n}\n";

        List<Finding> findings = only(analyze(source, EntityIndex.empty()), "JG-SPR-011");

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.MAJOR);
            assertThat(f.message()).contains("checked IOException");
            assertThat(f.remediation()).contains("rollbackFor = IOException.class");
        });
    }

    @Test
    void aCheckedExceptionDeclaredInAnotherFileOfTheModuleIsRecognised() throws IOException {
        Path sources = module.resolve("src/main/java");
        Files.createDirectories(sources.resolve("shop"));
        Files.writeString(sources.resolve("shop/BusinessException.java"), "package shop;\npublic class BusinessException extends Exception {\n}\n");
        Files.writeString(sources.resolve("shop/OutOfStock.java"), "package shop;\npublic class OutOfStock extends BusinessException {\n}\n");
        String source = "package shop;\n@org.springframework.transaction.annotation.Transactional\nclass S {\n"
                + "    public void m() throws OutOfStock {\n    }\n\n    @org.springframework.transaction.annotation.Transactional(rollbackFor = BusinessException.class)\n"
                + "    public void n() throws OutOfStock {\n    }\n}\n";

        List<Finding> findings = only(analyze(source, EntityIndex.scanning(List.of(sources))), "JG-SPR-011");

        assertThat(findings).singleElement().satisfies(f -> assertThat(f.line()).isEqualTo(4));
    }

    @Test
    void javaxImportsAreReportedAtTheirLineWithTheJakartaReplacement() {
        String source = "package a;\n\nimport javax.persistence.Entity;\nimport javax.sql.DataSource;\n\n@Entity\nclass E {\n}\n";

        List<Finding> findings = only(analyze(source, EntityIndex.empty()), "JG-SPR-013");

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(f.line()).isEqualTo(3);
            assertThat(f.remediation()).contains("jakarta.persistence");
        });
    }

    @Test
    void anUnpagedFindAllReturnedThroughAHelperNamesTheHelper() {
        String source = """
                @RestController
                class C {
                    private ProductRepository products;

                    @GetMapping("/p")
                    public java.util.List<Product> list() {
                        return load();
                    }

                    private java.util.List<Product> load() {
                        return products.findAll();
                    }
                }
                """;

        List<Finding> findings = only(analyze(source, EntityIndex.empty()), "JG-SPR-017");

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.line()).isEqualTo(7);
            assertThat(f.message()).contains("list()").contains("products.findAll()").contains("through load()");
        });
    }

    @Test
    void aRequestBodyTypeConstrainedInAnotherFileIsRecognised() throws IOException {
        Path sources = module.resolve("src/main/java");
        Files.createDirectories(sources.resolve("shop"));
        Files.writeString(sources.resolve("shop/OrderRequest.java"), "package shop;\nimport jakarta.validation.constraints.NotNull;\n"
                + "public record OrderRequest(@NotNull String sku) {\n}\n");
        String source = "package shop;\n@RestController\nclass C {\n    @PostMapping(\"/o\")\n    void order(@RequestBody OrderRequest request) {\n    }\n}\n";

        List<Finding> findings = only(analyze(source, EntityIndex.scanning(List.of(sources))), "JG-SPR-014");

        assertThat(findings).singleElement().satisfies(f -> assertThat(f.message()).contains("OrderRequest"));
    }
}
