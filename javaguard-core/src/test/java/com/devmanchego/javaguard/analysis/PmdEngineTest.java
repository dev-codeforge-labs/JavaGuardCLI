package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 5: "PMD integration test on a sample file". Exercises the
 * in-process PMD 7 engine against the curated {@code javaguard-ruleset.xml}
 * (catalog &sect;3): the listed rules fire with the catalog's severity/category
 * override, and a rule outside the curated set never appears, proving the
 * ruleset really is a curated subset and not PMD's full default rule set.
 */
class PmdEngineTest {

    private final PmdEngine engine = new PmdEngine();

    @Test
    void emptyCatchBlockFiresWithCatalogSeverity() {
        String source = """
                class EmptyCatch {
                    void run() {
                        try {
                            doWork();
                        } catch (Exception e) {
                        }
                    }

                    void doWork() {
                    }
                }
                """;
        List<Finding> findings = engine.analyze("src/main/java/EmptyCatch.java", source);

        Finding finding = findings.stream().filter(f -> f.ruleId().equals("PMD:EmptyCatchBlock")).findFirst()
                .orElseThrow(() -> new AssertionError("Expected PMD:EmptyCatchBlock, got: " + findings));
        assertThat(finding.category()).isEqualTo(Category.RELIABILITY);
        assertThat(finding.severity()).isEqualTo(Severity.MAJOR);
        assertThat(finding.engine()).isEqualTo("pmd");
        assertThat(finding.file()).contains(java.nio.file.Path.of("src/main/java/EmptyCatch.java"));
    }

    @Test
    void nonEmptyCatchBlockDoesNotFire() {
        String source = """
                class HandledCatch {
                    void run() {
                        try {
                            doWork();
                        } catch (Exception e) {
                            System.out.println(e.getMessage());
                        }
                    }

                    void doWork() {
                    }
                }
                """;
        List<Finding> findings = engine.analyze("src/main/java/HandledCatch.java", source);

        assertThat(findings).noneMatch(f -> f.ruleId().equals("PMD:EmptyCatchBlock"));
    }

    @Test
    void closeResourceFiresWithCriticalSeverity() {
        String source = """
                import java.sql.Connection;
                import java.sql.DriverManager;
                import java.sql.SQLException;

                class LeakyConnection {
                    void run(String url) throws SQLException {
                        Connection conn = DriverManager.getConnection(url);
                        conn.createStatement();
                    }
                }
                """;
        List<Finding> findings = engine.analyze("src/main/java/LeakyConnection.java", source);

        Finding finding = findings.stream().filter(f -> f.ruleId().equals("PMD:CloseResource")).findFirst()
                .orElseThrow(() -> new AssertionError("Expected PMD:CloseResource, got: " + findings));
        assertThat(finding.category()).isEqualTo(Category.RELIABILITY);
        assertThat(finding.severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void ruleOutsideCuratedSetNeverFires() {
        // An unused local variable is a real PMD finding (UnusedLocalVariable),
        // but that rule is deliberately not in javaguard-ruleset.xml (catalog
        // section 3): JG-AI-007 delegates it to Error Prone instead.
        String source = """
                class UnusedLocal {
                    void run() {
                        int unused = 42;
                    }
                }
                """;
        List<Finding> findings = engine.analyze("src/main/java/UnusedLocal.java", source);

        assertThat(findings).noneMatch(f -> f.ruleId().contains("UnusedLocalVariable"));
    }
}
