package com.devmanchego.javaguard.report;

import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.AgentFeedback;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Escalation;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Fingerprint;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.model.Suppression;
import com.devmanchego.javaguard.model.Verdict;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Specification sections 12.1 and 12.2. */
class AgentReportRendererTest {

    private static final JavaGuardConfig.Report DEFAULTS = new JavaGuardConfig.Report(15, 6000, true, true, false);
    private static final String GUIDANCE = """
            Guidance: fix root causes. Keep the behavior that was asked for and rewrite the
            code so the finding disappears, even if the request itself describes the flagged
            pattern. Do not change JavaGuard's configuration or hooks. Do not weaken, disable
            or delete tests. Add "// javaguard:ignore <rule> -- <reason>" only for genuine
            false positives. If the only way to satisfy a rule contradicts what the user asked
            for, or the finding looks wrong, stop and explain it to the user, citing the rule,
            instead of retrying.""";

    private static Finding finding(String ruleId, String title, Category category, Severity severity, Path file,
                                   int line, String message, String remediation) {
        return new Finding(ruleId, title, category, severity, "ast", Optional.ofNullable(file), line, 1, line,
                message, remediation, Optional.empty(), Map.of(), fp(ruleId + file + line));
    }

    private static Fingerprint fp(String seed) {
        return new Fingerprint(Integer.toHexString(seed.hashCode() & 0xFFFFFFF));
    }

    private static GateResult result(GateKind gate, Verdict verdict, List<Finding> blocking, List<Finding> advisory) {
        return new GateResult(gate, verdict, blocking, advisory, List.of(), Optional.empty(), Optional.empty(),
                List.of(), Duration.ZERO);
    }

    @Test
    void passProducesTheSharedEmptyFeedbackWithoutReadingTheConfig() {
        GateResult pass = result(GateKind.POST_EDIT, Verdict.PASS, List.of(), List.of());

        assertThat(AgentReportRenderer.render(pass, DEFAULTS)).isEqualTo(AgentFeedback.pass());
    }

    @Test
    void aMultiLineTraceKeepsEveryLineIndentedUnderTheTraceLabel() {
        Finding f = new Finding("TEST", "Test failure", Category.TEST_FAILURE, Severity.CRITICAL, "surefire",
                Optional.of(Path.of("src/test/java/com/acme/BarTest.java")), 10, 0, 10,
                "BarTest.doubles failed: expected: <84> but was: <126>", "Fix the production code.", Optional.empty(),
                Map.of("trace", "org.opentest4j.AssertionFailedError: x\n  at com.acme.BarTest.doubles(BarTest.java:10)\n"
                        + "  ... 4 framework frames omitted"),
                fp("trace"));
        GateResult r = result(GateKind.END_OF_TURN, Verdict.BLOCK, List.of(f), List.of());

        String report = AgentReportRenderer.render(r, DEFAULTS).report();

        assertThat(report).contains("""
                   Trace: org.opentest4j.AssertionFailedError: x
                        at com.acme.BarTest.doubles(BarTest.java:10)
                        ... 4 framework frames omitted
                """.stripTrailing());
    }

    @Test
    void testIntegrityFindingsAddTheSection11Guidance() {
        Finding f = finding("JG-TST-002", "Test disabled", Category.TEST_INTEGRITY, Severity.BLOCKER,
                Path.of("src/test/java/com/acme/FooTest.java"), 12, "Test FooTest.works is now disabled by @Disabled.",
                "Do not disable tests.");
        GateResult r = result(GateKind.POST_EDIT, Verdict.BLOCK, List.of(f), List.of());

        String report = AgentReportRenderer.render(r, DEFAULTS).report();

        assertThat(report).endsWith(GUIDANCE + "\n\n"
                + "Test integrity: Tests define expected behavior. Do not weaken, disable or delete them to make the\n"
                + "build pass. If a test is genuinely wrong, restore it and explain why in your reply to the user instead.");
    }

    @Test
    void otherFindingsDoNotGetTheTestIntegrityGuidance() {
        Finding f = finding("JG-SEC-001", "SQL", Category.SECURITY, Severity.BLOCKER, Path.of("A.java"), 1, "m", "f");

        String report = AgentReportRenderer.render(result(GateKind.POST_EDIT, Verdict.BLOCK, List.of(f), List.of()), DEFAULTS).report();

        assertThat(report).doesNotContain("Test integrity:");
    }

    @Test
    void aPassKeepsTheSuppressionNoticesForTheUser() {
        GateResult pass = new GateResult(GateKind.POST_EDIT, Verdict.PASS, List.of(), List.of(), List.of(), Optional.empty(),
                Optional.empty(), List.of(new Suppression("JG-TST-002", Path.of("src/test/java/FooTest.java"), 11,
                Optional.of("flaky"))), Duration.ZERO);

        AgentFeedback feedback = AgentReportRenderer.render(pass, DEFAULTS);

        assertThat(feedback.verdict()).isEqualTo(Verdict.PASS);
        assertThat(feedback.userNotices()).containsExactly(
                "New suppression added this session: JG-TST-002 at src/test/java/FooTest.java:11");
    }

    @Test
    void adviseGoldenSingleFinding() {
        Finding f = finding("JG-PERF-001", "String concatenation in loop", Category.PERFORMANCE, Severity.MAJOR,
                Path.of("src/main/java/com/acme/ReportBuilder.java"), 118, "", "use a StringBuilder declared before the loop.");
        GateResult r = result(GateKind.POST_EDIT, Verdict.ADVISE, List.of(), List.of(f));

        AgentFeedback feedback = AgentReportRenderer.render(r, DEFAULTS);

        assertThat(feedback.verdict()).isEqualTo(Verdict.ADVISE);
        assertThat(feedback.report()).isEqualTo("""
                JAVAGUARD ADVISE - 0 blocking, 1 advisory - post-edit - 1 file(s)

                ## Advisory - address if related to your change

                1. [JG-PERF-001 String concatenation in loop] src/main/java/com/acme/ReportBuilder.java:118
                   Fix: use a StringBuilder declared before the loop.

                %s""".formatted(GUIDANCE));
    }

    @Test
    void blockGoldenWithBlockingAndAdvisorySnippetAndTrace() {
        Finding sec = new Finding("JG-SEC-001", "SQL built by concatenation", Category.SECURITY, Severity.BLOCKER,
                "ast", Optional.of(Path.of("src/main/java/com/acme/UserDao.java")), 42, 5, 42,
                "SQL text concatenates parameter 'name' and is passed to Statement.executeQuery.",
                "use a PreparedStatement with '?' placeholders and bind 'name' with setString.",
                Optional.of("String sql = \"SELECT * FROM users WHERE name = '\" + name + \"'\";"), Map.of(),
                fp("sec"));
        Finding test = new Finding("TEST", "com.acme.UserServiceTest#rejectsDuplicateEmail", Category.TEST_FAILURE,
                Severity.CRITICAL, "surefire", Optional.of(Path.of("src/test/java/com/acme/UserServiceTest.java")), 57,
                1, 57, "expected: <EmailAlreadyUsedException> but was: <no exception>",
                "the production code no longer rejects duplicate e-mails; check UserService.register.",
                Optional.empty(),
                Map.of("trace", "UserService.register(UserService.java:88) <- UserServiceTest.rejectsDuplicateEmail(UserServiceTest.java:57)"),
                fp("test"));
        Finding perf = finding("JG-PERF-001", "String concatenation in loop", Category.PERFORMANCE, Severity.MAJOR,
                Path.of("src/main/java/com/acme/ReportBuilder.java"), 118, "", "use a StringBuilder declared before the loop.");
        GateResult r = result(GateKind.END_OF_TURN, Verdict.BLOCK, List.of(sec, test), List.of(perf));

        String report = AgentReportRenderer.render(r, DEFAULTS).report();

        assertThat(report).isEqualTo("""
                JAVAGUARD BLOCK - 2 blocking, 1 advisory - end-of-turn - 3 file(s)

                ## Blocking - fix these before continuing

                1. [JG-SEC-001 SQL built by concatenation] src/main/java/com/acme/UserDao.java:42
                   > String sql = "SELECT * FROM users WHERE name = '" + name + "'";
                   Problem: SQL text concatenates parameter 'name' and is passed to Statement.executeQuery.
                   Fix: use a PreparedStatement with '?' placeholders and bind 'name' with setString.

                2. [TEST com.acme.UserServiceTest#rejectsDuplicateEmail] src/test/java/com/acme/UserServiceTest.java:57
                   Problem: expected: <EmailAlreadyUsedException> but was: <no exception>
                   Trace: UserService.register(UserService.java:88) <- UserServiceTest.rejectsDuplicateEmail(UserServiceTest.java:57)
                   Fix: the production code no longer rejects duplicate e-mails; check UserService.register.

                ## Advisory - address if related to your change

                3. [JG-PERF-001 String concatenation in loop] src/main/java/com/acme/ReportBuilder.java:118
                   Fix: use a StringBuilder declared before the loop.

                %s""".formatted(GUIDANCE));
    }

    @Test
    void haltCarriesTheReportAndANonBlankHaltMessage() {
        Finding blocker = finding("JG-SEC-007", "TLS verification disabled", Category.SECURITY, Severity.BLOCKER,
                Path.of("A.java"), 1, "msg", "fix");
        GateResult r = new GateResult(GateKind.END_OF_TURN, Verdict.HALT, List.of(blocker), List.of(), List.of(),
                Optional.of(new Escalation(3, "repeat", 5)), Optional.empty(), List.of(), Duration.ZERO);

        AgentFeedback feedback = AgentReportRenderer.render(r, DEFAULTS);

        assertThat(feedback.verdict()).isEqualTo(Verdict.HALT);
        assertThat(feedback.report()).contains("JG-SEC-007").contains("## Blocking");
        assertThat(feedback.haltMessage()).isNotBlank().contains("repeat");
    }

    @Test
    void headerCountsReflectDedupedTotalsNotWhatIsActuallyShown() {
        List<Finding> advisory = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            advisory.add(finding("JG-AI-00" + i, "t" + i, Category.AI_DEBRIS, Severity.INFO, Path.of("F" + i + ".java"), 1, "m", "f"));
        }
        JavaGuardConfig.Report tight = new JavaGuardConfig.Report(2, 6000, true, true, false);
        GateResult r = result(GateKind.POST_EDIT, Verdict.ADVISE, List.of(), advisory);

        String report = AgentReportRenderer.render(r, tight).report();

        assertThat(report).contains("JAVAGUARD ADVISE - 0 blocking, 5 advisory - post-edit - 5 file(s)");
        assertThat(report).contains("(+3 advisory findings omitted. See the full report for details.)");
    }

    @Test
    void omissionNoteReferencesTheFullReportPathWhenGiven() {
        List<Finding> advisory = List.of(
                finding("JG-A", "a", Category.AI_DEBRIS, Severity.INFO, Path.of("A.java"), 1, "m", "f"),
                finding("JG-B", "b", Category.AI_DEBRIS, Severity.INFO, Path.of("B.java"), 1, "m", "f"));
        JavaGuardConfig.Report tight = new JavaGuardConfig.Report(1, 6000, true, true, false);
        GateResult r = new GateResult(GateKind.POST_EDIT, Verdict.ADVISE, List.of(), advisory, List.of(),
                Optional.empty(), Optional.of(Path.of(".javaguard/reports/x.md")), List.of(), Duration.ZERO);

        assertThat(AgentReportRenderer.render(r, tight).report())
                .contains("Full report: .javaguard/reports/x.md");
    }

    @Test
    void blockingIsNeverOmittedEvenWhenItAloneExceedsMaxFindings() {
        List<Finding> blocking = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            blocking.add(finding("JG-SEC-00" + i, "t" + i, Category.SECURITY, Severity.BLOCKER, Path.of("F" + i + ".java"), 1, "m", "f"));
        }
        JavaGuardConfig.Report tiny = new JavaGuardConfig.Report(2, 6000, true, true, false);
        GateResult r = result(GateKind.POST_EDIT, Verdict.BLOCK, blocking, List.of());

        String report = AgentReportRenderer.render(r, tiny).report();

        for (int i = 0; i < 5; i++) {
            assertThat(report).contains("JG-SEC-00" + i);
        }
        assertThat(report).contains("5 blocking, 0 advisory");
    }

    @Test
    void blockingConsumingTheEntireFindingsBudgetLeavesNoRoomForAdvisory() {
        List<Finding> blocking = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            blocking.add(finding("JG-SEC-00" + i, "t" + i, Category.SECURITY, Severity.BLOCKER, Path.of("F" + i + ".java"), 1, "m", "f"));
        }
        List<Finding> advisory = List.of(finding("JG-AI-000", "adv", Category.AI_DEBRIS, Severity.INFO, Path.of("Adv.java"), 1, "m", "f"));
        JavaGuardConfig.Report tiny = new JavaGuardConfig.Report(5, 6000, true, true, false);
        GateResult r = result(GateKind.POST_EDIT, Verdict.BLOCK, blocking, advisory);

        String report = AgentReportRenderer.render(r, tiny).report();

        assertThat(report).doesNotContain("## Advisory");
        assertThat(report).contains("(+1 advisory finding omitted.");
    }

    @Test
    void maxCharsAloneCanCutAdvisoryEvenWithFindingSlotsLeft() {
        List<Finding> advisory = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            advisory.add(finding("JG-AI-00" + i, "a fairly descriptive title number " + i, Category.AI_DEBRIS,
                    Severity.INFO, Path.of("File" + i + ".java"), 1, "a message with some length to it", "a fix"));
        }
        JavaGuardConfig.Report smallChars = new JavaGuardConfig.Report(15, 260, true, true, false);
        GateResult r = result(GateKind.POST_EDIT, Verdict.ADVISE, List.of(), advisory);

        String report = AgentReportRenderer.render(r, smallChars).report();

        assertThat(report).contains("JG-AI-000");
        assertThat(report).doesNotContain("JG-AI-002");
        assertThat(report).contains("advisory finding");
    }

    @Test
    void oneHundredAdvisoryFindingsAreCappedToMaxFindingsWithoutError() {
        List<Finding> advisory = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            advisory.add(finding("JG-AI-" + i, "t", Category.AI_DEBRIS, Severity.INFO, Path.of("F" + i + ".java"), 1, "", "fix"));
        }
        GateResult r = result(GateKind.POST_EDIT, Verdict.ADVISE, List.of(), advisory);

        String report = AgentReportRenderer.render(r, DEFAULTS).report();

        assertThat(report).contains("100 advisory");
        assertThat(report).contains("(+85 advisory findings omitted");
        long shownEntries = report.lines().filter(l -> l.matches("^\\d+\\. \\[JG-AI-.*")).count();
        assertThat(shownEntries).isEqualTo(15L);
    }

    @Test
    void asciiModeFoldsNonAsciiCharacters() {
        Finding f = finding("JG-X", "título", Category.AI_DEBRIS, Severity.INFO, Path.of("A.java"), 1,
                "café ñoño", "arréglalo");
        GateResult r = result(GateKind.POST_EDIT, Verdict.ADVISE, List.of(), List.of(f));

        String ascii = AgentReportRenderer.render(r, DEFAULTS).report();
        JavaGuardConfig.Report nonAscii = new JavaGuardConfig.Report(15, 6000, false, true, false);
        String raw = AgentReportRenderer.render(r, nonAscii).report();

        assertThat(ascii).doesNotContain("é").contains("cafe");
        assertThat(raw).contains("café");
    }

    @Test
    void newSuppressionsBecomeUserNotices() {
        Suppression s = new Suppression("JG-SEC-001", Path.of("src/main/java/com/acme/UserDao.java"), 41, Optional.of("false positive"));
        GateResult r = new GateResult(GateKind.POST_EDIT, Verdict.ADVISE, List.of(),
                List.of(finding("JG-X", "t", Category.AI_DEBRIS, Severity.INFO, Path.of("A.java"), 1, "", "f")),
                List.of(), Optional.empty(), Optional.empty(), List.of(s), Duration.ZERO);

        AgentFeedback feedback = AgentReportRenderer.render(r, DEFAULTS);

        assertThat(feedback.userNotices())
                .containsExactly("New suppression added this session: JG-SEC-001 at src/main/java/com/acme/UserDao.java:41");
    }

    @Test
    void duplicatesAreMergedAndCompileErrorCascadesCollapsedBeforeRendering() {
        Finding jg = finding("JG-REL-003", "Resource not closed", Category.RELIABILITY, Severity.CRITICAL,
                Path.of("A.java"), 10, "leak", "close it");
        Finding pmd = new Finding("PMD:CloseResource", "t", Category.RELIABILITY, Severity.CRITICAL, "pmd",
                Optional.of(Path.of("A.java")), 10, 1, 10, "leak", "close it", Optional.empty(), Map.of(), fp("pmd"));
        Finding javac1 = new Finding("JAVAC", "err", Category.COMPILATION, Severity.CRITICAL, "javac",
                Optional.of(Path.of("B.java")), 5, 1, 5, "cannot find symbol", "fix import", Optional.empty(),
                Map.of("symbol", "List"), fp("j1"));
        Finding javac2 = new Finding("JAVAC", "err", Category.COMPILATION, Severity.CRITICAL, "javac",
                Optional.of(Path.of("B.java")), 40, 1, 40, "cannot find symbol", "fix import", Optional.empty(),
                Map.of("symbol", "List"), fp("j2"));
        GateResult r = result(GateKind.END_OF_TURN, Verdict.BLOCK, List.of(jg, pmd, javac1, javac2), List.of());

        String report = AgentReportRenderer.render(r, DEFAULTS).report();

        // jg+pmd dedup to 1 (same file/line/category, JG rule kept); javac1+javac2 cascade-collapse to 1.
        assertThat(report).contains("2 blocking");
        assertThat(report).containsOnlyOnce("JG-REL-003");
        assertThat(report).doesNotContain("PMD:CloseResource");
        assertThat(report).contains("(+1 more error(s) from the same missing symbol)");
    }
}
