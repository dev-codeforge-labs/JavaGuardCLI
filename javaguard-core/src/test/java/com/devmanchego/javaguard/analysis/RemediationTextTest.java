package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Finding;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The remediation is what a blocked agent acts on, so it must give a concrete way out and never one that is blocked itself:
 * JavaGuard's configuration is changed only by the user (JG-SUP-003).
 */
class RemediationTextTest {

    private static final AstCheckEngine ENGINE = new AstCheckEngine();

    private static List<Finding> findings(String ruleId, String source) {
        return ENGINE.analyze("src/main/java/a/A.java", source).stream().filter(f -> f.ruleId().equals(ruleId)).toList();
    }

    @Test
    void consoleOutputInAClassThatAlreadyLogsPointsToItsLogger() {
        List<Finding> found = findings("JG-AI-001", """
                package a;
                import org.slf4j.Logger;
                class A { void f() { System.out.println("x"); } }
                """);

        assertThat(found).singleElement().extracting(Finding::remediation).asString()
                .contains("SLF4J logger").doesNotContain("ask the user");
    }

    @Test
    void consoleOutputWithoutALoggerLetsTheUserChooseBetweenSlf4jAndConsoleOutput() {
        List<Finding> found = findings("JG-AI-001", """
                package a;
                class A { void f() { System.out.println("x"); } }
                """);

        assertThat(found).singleElement().extracting(Finding::remediation).asString()
                .contains("LoggerFactory.getLogger").contains("do not add a logging library on your own")
                .contains("ask the user").contains("the user turns JG-AI-001 off");
    }

    @Test
    void concatenatedExpressionsGetTheBindingFormOfTheirOwnApi() {
        List<Finding> ldap = findings("JG-SEC-013", """
                package a;
                import javax.naming.directory.DirContext;
                class A { void f(DirContext ctx, String u) throws Exception { ctx.search("ou=x", "(uid=" + u + ")", null); } }
                """);
        List<Finding> spel = findings("JG-SEC-013", """
                package a;
                import org.springframework.expression.ExpressionParser;
                class A { void f(ExpressionParser p, String u) { p.parseExpression("name == '" + u + "'"); } }
                """);

        assertThat(ldap).singleElement().extracting(Finding::remediation).asString().contains("{0}");
        assertThat(spel).singleElement().extracting(Finding::remediation).asString().contains("EvaluationContext");
    }

    @Test
    void everyRemediationIsConcreteAndLeavesJavaGuardsConfigurationToTheUser() throws IOException {
        List<Finding> all = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/test/resources/rules"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java") && p.getParent().endsWith("bad")).toList()) {
                all.addAll(ENGINE.analyze("src/main/java/" + file.getFileName(), Files.readString(file, StandardCharsets.UTF_8)));
            }
        }

        assertThat(all).isNotEmpty().allSatisfy(f -> {
            assertThat(f.remediation()).as(f.ruleId()).isNotBlank();
            if (f.remediation().contains("javaguard.yml")) {
                assertThat(f.remediation()).as(f.ruleId() + " must leave the configuration to the user").contains("user");
            }
        });
    }
}
