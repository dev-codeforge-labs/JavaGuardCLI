package com.devmanchego.javaguard.consult;

import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.AgentKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 14: command templates and the choice of a consultant different from the current agent, available on PATH. */
class ConsultSupportTest {

    @TempDir
    Path root;

    private JavaGuardConfig config(String yaml) throws Exception {
        Files.writeString(root.resolve("javaguard.yml"), yaml);
        return ConfigLoader.load(ConfigSources.standard(root, Optional.empty(), root.resolve("home"), Map.of())).config();
    }

    private Path fakeExecutable(String dirName, String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(dirName));
        Path exe = dir.resolve(name);
        Files.writeString(exe, "x");
        exe.toFile().setExecutable(true);
        return exe;
    }

    @Test
    void defaultTemplatesAreReadOnlyAndTakeTheHandoffOnStdin() {
        assertThat(ConsultCommands.template(AgentKind.GEMINI, Map.of())).containsExactly("gemini", "-p", "<instruction>",
                "--approval-mode", "plan");
        assertThat(ConsultCommands.template(AgentKind.CODEX, Map.of())).containsSubsequence("exec", "--sandbox", "read-only");
        assertThat(ConsultCommands.render(ConsultCommands.template(AgentKind.CLAUDE, Map.of()), "claude-opus-5-5"))
                .containsSubsequence("-p", ConsultCommands.INSTRUCTION, "--permission-mode", "plan", "--model", "claude-opus-5-5");
    }

    @Test
    void anOverrideReplacesTheTemplateAndGroupsQuotedWords() {
        List<String> tokens = ConsultCommands.template(AgentKind.GEMINI, Map.of("gemini", "my-tool --flag \"two words\" <instruction>"));

        assertThat(tokens).containsExactly("my-tool", "--flag", "two words", "<instruction>");
    }

    @Test
    void theFirstAvailableAgentOtherThanTheCurrentOneIsChosen() throws Exception {
        Path bin = fakeExecutable("bin", "codex").getParent();
        fakeExecutable("bin", "claude");
        JavaGuardConfig config = config("consult:\n  enabled: true\n  order: [gemini, codex, claude]\n");
        ConsultSupport support = new ConsultSupport(config.consult(), Map.of("PATH", bin.toString()), false);

        assertThat(support.select("claude").orElseThrow().agent()).isEqualTo(AgentKind.CODEX); // gemini is not installed
        assertThat(support.select("codex").orElseThrow().agent()).isEqualTo(AgentKind.CLAUDE);
        assertThat(support.select("claude").orElseThrow().command().get(0)).endsWith("codex");
    }

    @Test
    void nothingIsChosenWhenDisabledOrWhenOnlyTheCurrentAgentIsInstalled() throws Exception {
        Path bin = fakeExecutable("bin", "claude").getParent();
        Map<String, String> env = Map.of("PATH", bin.toString());

        JavaGuardConfig disabled = config("consult:\n  enabled: false\n");
        assertThat(new ConsultSupport(disabled.consult(), env, false).select("codex")).isEmpty();

        JavaGuardConfig enabled = config("consult:\n  enabled: true\n");
        assertThat(new ConsultSupport(enabled.consult(), env, false).select("claude")).isEmpty();
    }

    @Test
    void availabilityReportsWhereEachAgentWasFound() throws Exception {
        Path bin = fakeExecutable("bin", "gemini").getParent();
        JavaGuardConfig config = config("consult:\n  enabled: true\n");

        var availability = new ConsultSupport(config.consult(), Map.of("PATH", bin.toString()), false).availability();

        assertThat(availability.get(AgentKind.GEMINI)).isPresent();
        assertThat(availability.get(AgentKind.CODEX)).isEmpty();
    }
}
