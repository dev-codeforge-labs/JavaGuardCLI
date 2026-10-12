package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.cli.ExitCode;
import com.devmanchego.javaguard.loop.LoopGuard;
import com.devmanchego.javaguard.loop.LoopSettings;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 13: {@code javaguard handoff} writes the handoff of a session on demand. */
class HandoffCommandTest extends CommandTestBase {

    private static final String REL = "src/A.java";

    private void seedBlockedSession(String session, int runs) throws Exception {
        createMavenProject();
        Path file = writeProjectFile(REL, "class A { int f() { return g(); } }\n");
        JavaGuardPaths paths = new JavaGuardPaths(projectRoot);
        paths.ensureLayout();
        Finding finding = new Finding("JAVAC", "Compilation error", Category.COMPILATION, Severity.CRITICAL, "maven",
                Optional.of(Path.of(REL)), 1, 1, 1, "cannot find symbol: method g()", "Declare g().", Optional.empty(), Map.of(),
                Fingerprints.of("JAVAC", REL, "g"));
        for (int i = 0; i < runs; i++) {
            Files.writeString(file, "class A { int f() { return g(); } } // " + i + "\n", StandardCharsets.UTF_8);
            new LoopGuard(paths, new LoopSettings(3, 6, 5, 12, 3), 3, Clock.systemUTC(), false).apply(
                    new GateResult(GateKind.POST_EDIT, Verdict.BLOCK, List.of(finding), List.of(), List.of(), Optional.empty(),
                            Optional.empty(), List.of(), Duration.ZERO),
                    new LoopGuard.Input(session, GateKind.POST_EDIT, Map.of(REL, file), false, "claude", Optional.empty()));
        }
    }

    @Test
    void writesTheHandoffOfTheMostRecentSession() throws Exception {
        seedBlockedSession("sess-77", 2);

        int exit = run("handoff");

        assertThat(exit).isZero();
        assertThat(stdout()).contains("Handoff written to .javaguard/handoff/HANDOFF-sess-77-");
        assertThat(projectRoot.resolve("AI_HANDOFF_CONTEXT.md")).exists();
        assertThat(Files.readString(projectRoot.resolve("AI_HANDOFF_CONTEXT.md"))).contains("Agent: claude")
                .contains("cannot find symbol: method g()");
    }

    @Test
    void sessionOptionSelectsTheSession() throws Exception {
        seedBlockedSession("sess-88", 1);

        assertThat(run("handoff", "--session", "sess-88")).isZero();
        clearOutput();
        assertThat(run("handoff", "--session", "other")).isEqualTo(ExitCode.ENVIRONMENT_ERROR.code());
        assertThat(stderr()).contains("no recorded gate runs");
    }

    @Test
    void withoutAnySessionItIsAUsageError() throws Exception {
        createMavenProject();

        assertThat(run("handoff")).isEqualTo(ExitCode.USAGE_ERROR.code());
        assertThat(stderr()).contains("no session");
    }

    private static String javaExecutable() {
        Path executable = Path.of(System.getProperty("java.home"), "bin", "java");
        return (Files.exists(executable) ? executable : Path.of(executable + ".exe")).toString();
    }

    /** A consultant that answers with the JDK's version text, whatever it is asked. */
    private void consultantAnswering() throws Exception {
        writeProjectFile("javaguard.yml", "consult:\n  commands:\n    gemini: '\"" + javaExecutable() + "\" --version'\n");
    }

    @Test
    void consultAsksTheSecondModelAndWritesTheSolution() throws Exception {
        seedBlockedSession("sess-99", 2);
        consultantAnswering();

        assertThat(run("handoff", "--consult", "gemini")).isZero();

        assertThat(stdout()).contains("Solution written to .javaguard/handoff/SOLUTION-sess-99.md");
        assertThat(Files.readString(projectRoot.resolve(".javaguard/handoff/SOLUTION-sess-99.md"))).isNotBlank();
    }

    @Test
    void consultWithAnUnavailableConsultantFailsWithAnEnvironmentError() throws Exception {
        seedBlockedSession("sess-99", 1);
        writeProjectFile("javaguard.yml", "consult:\n  commands:\n    gemini: 'no-such-tool-xyz <instruction>'\n");

        assertThat(run("handoff", "--consult", "gemini")).isEqualTo(ExitCode.ENVIRONMENT_ERROR.code());
        assertThat(stderr()).contains("consultation failed").contains("not found");
    }

    @Test
    void consultRejectsAnUnknownAgent() throws Exception {
        seedBlockedSession("sess-99", 1);

        assertThat(run("handoff", "--consult", "gpt")).isEqualTo(ExitCode.USAGE_ERROR.code());
    }

    @Test
    void theInternalWorkerCommandRunsTheConsultationForASession() throws Exception {
        seedBlockedSession("sess-55", 2);
        assertThat(run("handoff", "--session", "sess-55")).isZero();
        consultantAnswering();

        assertThat(run("consult-run", "--session", "sess-55", "--agent", "gemini")).isZero();

        assertThat(projectRoot.resolve(".javaguard/handoff/SOLUTION-sess-55.md")).exists();
        assertThat(Files.readString(projectRoot.resolve(".javaguard/state/sess-55/consult.json"))).contains("DONE");
    }
}
