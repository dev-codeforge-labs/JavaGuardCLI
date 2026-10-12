package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.analysis.CheckRegistry;
import com.devmanchego.javaguard.analysis.PmdEngine;
import com.devmanchego.javaguard.integrity.TestIntegrityEngine;
import com.devmanchego.javaguard.cli.ExitCode;
import com.devmanchego.javaguard.cli.Main;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 5: {@code javaguard rules} lists every AST + PMD rule with its effective configuration. */
class RulesCommandTest {

    @TempDir
    Path root;

    private PrintStream originalOut;
    private PrintStream originalErr;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private int run(String... args) {
        String[] full = new String[args.length + 2];
        full[0] = "--project-dir";
        full[1] = root.toString();
        System.arraycopy(args, 0, full, 2, args.length);
        return new CommandLine(new Main()).execute(full);
    }

    @Test
    void listsEverySynAstAndPmdRule() {
        int exit = run("rules");

        assertThat(exit).isEqualTo(ExitCode.SUCCESS.code());
        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("JG-SYN-001").contains("JG-SEC-001").contains("JG-AI-001").contains("PMD:CloseResource");
        // + JG-ARCH-001, JG-API-001, JG-DB-001..003, JG-CFG-001, JG-CFG-002, JG-API-002..004
        int expectedCount = 1 + CheckRegistry.ALL.size() + PmdEngine.RULES.size() + TestIntegrityEngine.RULES.size() + 2 + 3 + 2 + 3;
        assertThat(output).contains(expectedCount + " rule(s)");
        assertThat(output).contains("JG-DB-001").contains("JG-CFG-002").contains("DATABASE").contains("CONFIGURATION");
    }

    @Test
    void listsAllFourteenTestIntegrityRulesAcrossBothEngines() {
        run("rules", "--category", "test_integrity");

        String output = out.toString(StandardCharsets.UTF_8);
        for (int n = 1; n <= 14; n++) {
            assertThat(output).contains(String.format("JG-TST-%03d", n));
        }
        assertThat(output).contains("14 rule(s)");
    }

    @Test
    void listsTheSpringJpaArchitectureAndApiRulesWithTheirGates() {
        run("rules");

        String output = out.toString(StandardCharsets.UTF_8);
        for (int n = 1; n <= 10; n++) {
            assertThat(output).contains(String.format("JG-SPR-%03d", n));
        }
        assertThat(output).containsPattern("JG-ARCH-001\\s+ARCHITECTURE\\s+MAJOR\\s+ast\\s+F");
        assertThat(output).containsPattern("JG-API-001\\s+API_COMPATIBILITY\\s+MAJOR\\s+diff\\s+E");
    }

    @Test
    void categoryFilterKeepsOnlyThatCategory() {
        int exit = run("rules", "--category", "security");

        assertThat(exit).isEqualTo(ExitCode.SUCCESS.code());
        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("JG-SEC-001");
        assertThat(output).doesNotContain("JG-PERF-001");
    }

    @Test
    void unknownCategoryIsAUsageError() {
        int exit = run("rules", "--category", "not-a-category");

        assertThat(exit).isEqualTo(ExitCode.USAGE_ERROR.code());
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("unknown category");
    }

    @Test
    void disabledRuleIsHiddenByEnabledFilterButShownOtherwise() throws Exception {
        Files.writeString(root.resolve("javaguard.yml"), """
                rules:
                  JG-SEC-001:
                    enabled: false
                """);

        int allExit = run("rules");
        assertThat(allExit).isEqualTo(ExitCode.SUCCESS.code());
        String allOutput = out.toString(StandardCharsets.UTF_8);
        assertThat(allOutput).contains("JG-SEC-001");

        out.reset();
        int enabledExit = run("rules", "--enabled");
        assertThat(enabledExit).isEqualTo(ExitCode.SUCCESS.code());
        String enabledOutput = out.toString(StandardCharsets.UTF_8);
        assertThat(enabledOutput).doesNotContain("JG-SEC-001");
        assertThat(enabledOutput).contains("JG-SEC-002");
    }
}
