package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.change.ChangeTracker;
import com.devmanchego.javaguard.change.GitClient;
import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.model.ChangeType;
import com.devmanchego.javaguard.model.EditFragment;
import com.devmanchego.javaguard.model.FileChange;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.HookEvent;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import org.junit.jupiter.api.BeforeEach;
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
 * Plan Phase 10, end to end through the POST_EDIT gate on a copy of the real {@code maven-simple} fixture: an
 * agent that weakens a test or the build is blocked (specification &sect;11.1, catalog &sect;1.7), an agent that
 * refactors one is not.
 */
class TestIntegrityGateTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/maven-simple").toAbsolutePath().normalize();
    private static final String APP_TEST = "src/test/java/com/acme/simple/AppTest.java";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path home;

    @TempDir
    Path project;

    private JavaGuardPaths paths;
    private ChangeTracker tracker;

    @BeforeEach
    void copyFixture() throws IOException {
        try (Stream<Path> walk = Files.walk(FIXTURE)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path target = project.resolve(FIXTURE.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else if (!("/" + FIXTURE.relativize(source)).replace('\\', '/').contains("/target/")) {
                    Files.copy(source, target);
                }
            }
        }
        paths = new JavaGuardPaths(project);
        paths.ensureLayout();
        tracker = new ChangeTracker(paths, GitClient.none(), CLOCK);
    }

    private JavaGuardConfig config() throws Exception {
        // A slow shared runner must not exhaust the 3 s fast-gate budget and turn a finding into a pass.
        return ConfigLoader.load(ConfigSources.standard(project, Optional.empty(), home,
                Map.of("JAVAGUARD_FASTGATE_BUDGETMILLIS", "120000"))).config();
    }

    private static HookEvent event(GateKind gate, List<FileChange> changes) {
        return new HookEvent(AgentKind.CLAUDE, gate, "s1", Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath(),
                Optional.empty(), "Edit", changes, false, Optional.empty(), CLOCK.instant());
    }

    /** The agent replaces {@code from} by {@code to} in a project file; the gate sees the edit as Claude's Edit tool reports it. */
    private GateResult edit(String relative, String from, String to) throws Exception {
        Path file = project.resolve(relative);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(text).contains(from);
        Files.writeString(file, text.replace(from, to), StandardCharsets.UTF_8);
        return new PostEditGate(paths, config(), tracker, CLOCK).run(event(GateKind.POST_EDIT,
                List.of(FileChange.edit(file, List.of(new EditFragment(from, to, false))))));
    }

    private static List<String> ruleIds(GateResult result) {
        return Stream.concat(result.blocking().stream(), result.advisory().stream()).map(Finding::ruleId).toList();
    }

    @Test
    void addingDisabledToATestIsBlocked() throws Exception {
        GateResult result = edit(APP_TEST, "    @Test\n", "    @Test\n    @Disabled\n");

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).extracting(Finding::ruleId).contains("JG-TST-002");
        Finding f = result.blocking().stream().filter(x -> x.ruleId().equals("JG-TST-002")).findFirst().orElseThrow();
        assertThat(f.file()).contains(Path.of(APP_TEST));
        assertThat(f.message()).contains("AppTest.greetsByName").contains("@Disabled");
    }

    private static final String ASSERT_LINE = "        assertEquals(\"Hello, World!\", new App().greet(\"World\"));\n";

    /** A tracker whose git answers {@code HEAD:} with the fixture's file, as in a real repository. */
    private void withGitHead() {
        tracker = new ChangeTracker(paths, file -> {
            try {
                return Optional.of(Files.readAllBytes(FIXTURE.resolve(APP_TEST)));
            } catch (IOException e) {
                return Optional.empty();
            }
        }, CLOCK);
    }

    @Test
    void emptyingATestBodyIsBlockedAsOneFindingNotThreeInAGitProject() throws Exception {
        withGitHead();

        GateResult result = edit(APP_TEST, ASSERT_LINE, "");

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(ruleIds(result)).contains("JG-TST-006").doesNotContain("JG-TST-003");
    }

    @Test
    void emptyingATestBodyIsBlockedInAProjectWithoutGitThanksToThePreEditSnapshot() throws Exception {
        Path file = project.resolve(APP_TEST);
        FileChange change = FileChange.edit(file, List.of(new EditFragment(ASSERT_LINE, "", false)));
        tracker.snapshotBeforeEdit(event(GateKind.PRE_EDIT, List.of(change)));
        Files.writeString(file, Files.readString(file).replace(ASSERT_LINE, ""));

        GateResult result = new PostEditGate(paths, config(), tracker, CLOCK).run(event(GateKind.POST_EDIT, List.of(change)));

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(ruleIds(result)).contains("JG-TST-006");
    }

    @Test
    void aPureDeletionWithNeitherGitNorSnapshotHasNoBaselineSoTheDiffRulesStaySilent() throws Exception {
        GateResult result = edit(APP_TEST, ASSERT_LINE, "");

        assertThat(ruleIds(result)).noneMatch(id -> id.startsWith("JG-TST-00") || id.equals("JG-TST-012"));
    }

    @Test
    void anAssertionAddedAndRemovedAgainWithinTheSessionIsNotReported() throws Exception {
        edit(APP_TEST, "        assertEquals(\"Hello, World!\", new App().greet(\"World\"));\n",
                "        assertEquals(\"Hello, World!\", new App().greet(\"World\"));\n        assertEquals(\"Hello, Bob!\", new App().greet(\"Bob\"));\n");

        GateResult result = edit(APP_TEST, "        assertEquals(\"Hello, Bob!\", new App().greet(\"Bob\"));\n", "");

        // The session baseline is the file as it was before the agent's FIRST edit: two assertions were added, one removed.
        assertThat(result.verdict()).isNotEqualTo(Verdict.BLOCK);
    }

    @Test
    void deletingAnAssertionTheOriginalTestHadIsBlocked() throws Exception {
        GateResult result = edit(APP_TEST, "        assertEquals(\"Hello, World!\", new App().greet(\"World\"));\n",
                "        new App().greet(\"World\");\n");

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).extracting(Finding::ruleId).contains("JG-TST-003");
    }

    @Test
    void changingTheExpectedValueIsAdvisoryNotBlocking() throws Exception {
        GateResult result = edit(APP_TEST, "\"Hello, World!\", new App()", "\"Hi, World!\", new App()");

        assertThat(result.blocking()).extracting(Finding::ruleId).doesNotContain("JG-TST-004");
        assertThat(result.advisory()).extracting(Finding::ruleId).contains("JG-TST-004");
    }

    @Test
    void renamingATestAndRefactoringItsAssertionsIsNotReported() throws Exception {
        GateResult result = edit(APP_TEST, "void greetsByName() {", "void greetsTheGivenName() {");

        assertThat(ruleIds(result)).noneMatch(id -> id.startsWith("JG-TST-"));
    }

    @Test
    void removingATestMethodIsBlocked() throws Exception {
        GateResult result = edit(APP_TEST, "    @Test\n    void greetsByName() {\n        assertEquals(\"Hello, World!\", new App().greet(\"World\"));\n    }\n",
                "    int helper() {\n        return 1;\n    }\n");

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).extracting(Finding::ruleId).contains("JG-TST-001");
    }

    @Test
    void addingATrivialAssertionIsBlocked() throws Exception {
        GateResult result = edit(APP_TEST, "    }\n}\n", "        assertTrue(true);\n    }\n}\n");

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).extracting(Finding::ruleId).contains("JG-TST-005");
    }

    @Test
    void skippingTestsInThePomIsBlocked() throws Exception {
        GateResult result = edit("pom.xml", "</properties>", "<skipTests>true</skipTests></properties>");

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).extracting(Finding::ruleId).containsExactly("JG-TST-009");
        Finding f = result.blocking().get(0);
        assertThat(f.file()).contains(Path.of("pom.xml"));
        assertThat(f.line()).isGreaterThan(0);
        assertThat(f.message()).contains("<skipTests>true</skipTests>");
    }

    @Test
    void anUnrelatedPomEditIsNotReported() throws Exception {
        GateResult result = edit("pom.xml", "<version>1.0.0</version>", "<version>1.0.1</version>");

        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void aDeletedTestSourceIsBlockedWhenTheSessionHadASnapshotOfIt() throws Exception {
        Path file = project.resolve(APP_TEST);
        FileChange deletion = FileChange.rewrite(file, ChangeType.DELETE);
        tracker.snapshotBeforeEdit(event(GateKind.PRE_EDIT, List.of(deletion))); // what the optional PreToolUse hook does
        Files.delete(file);

        GateResult result = new PostEditGate(paths, config(), tracker, CLOCK).run(event(GateKind.POST_EDIT, List.of(deletion)));

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).extracting(Finding::ruleId).containsExactly("JG-TST-008");
    }

    @Test
    void editingProductionCodeNeverRaisesATestIntegrityFinding() throws Exception {
        GateResult result = edit("src/main/java/com/acme/simple/App.java", "return \"Hello, \" + name + \"!\";",
                "return \"Hello, \" + name + \"?\";");

        assertThat(ruleIds(result)).noneMatch(id -> id.startsWith("JG-TST-") && !id.equals("JG-TST-013"));
    }

    @Test
    void aRuleSwitchedOffInTheConfigurationStaysSilent() throws Exception {
        Files.writeString(project.resolve("javaguard.yml"), "rules:\n  JG-TST-002: { enabled: false }\n");

        GateResult result = edit(APP_TEST, "    @Test\n", "    @Test\n    @Disabled\n");

        assertThat(ruleIds(result)).doesNotContain("JG-TST-002");
    }

    @Test
    void anAgentAddedSuppressionSilencesTheFindingButIsStillListedForTheUser() throws Exception {
        GateResult result = edit(APP_TEST, "    @Test\n",
                "    // javaguard:ignore JG-TST-002 -- flaky on CI\n    @Test\n    @Disabled\n");

        assertThat(ruleIds(result)).doesNotContain("JG-TST-002");
        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        assertThat(result.newSuppressions()).extracting(x -> x.ruleId()).contains("JG-TST-002");
    }
}
