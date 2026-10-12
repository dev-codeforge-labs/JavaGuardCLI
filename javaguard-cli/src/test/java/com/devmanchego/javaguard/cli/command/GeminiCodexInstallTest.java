package com.devmanchego.javaguard.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 16: {@code install}/{@code uninstall}/{@code doctor} for Gemini CLI and Codex. */
class GeminiCodexInstallTest extends CommandTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EXE = "C:/tools/javaguard/javaguard.exe";

    private Path geminiFile() {
        return projectRoot.resolve(".gemini/settings.json");
    }

    private Path codexFile() {
        return projectRoot.resolve(".codex/hooks.json");
    }

    private JsonNode read(Path file) throws IOException {
        return JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
    }

    private int install(String agent, String... extra) {
        String[] args = new String[extra.length + 5];
        args[0] = "install";
        args[1] = "--agent";
        args[2] = agent;
        args[3] = "--command";
        args[4] = EXE;
        System.arraycopy(extra, 0, args, 5, extra.length);
        return run(args);
    }

    private String doctorTable() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        new DoctorCommand().run(projectRoot, null, userHome, Map.of(), new PrintStream(captured, true, StandardCharsets.UTF_8));
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void installGeminiWritesTheContractSettings() throws Exception {
        Files.createDirectories(projectRoot.resolve(".git"));

        assertThat(install("gemini")).isZero();

        JsonNode settings = read(geminiFile());
        assertThat(settings.at("/hooks/AfterTool/0/matcher").asText()).isEqualTo("write_file|replace");
        // Gemini CLI runs hooks through PowerShell on Windows, where the quoted path needs the call operator.
        String callOperator = System.getProperty("os.name", "").toLowerCase().contains("win") ? "& " : "";
        assertThat(settings.at("/hooks/AfterTool/0/hooks/0/command").asText())
                .isEqualTo(callOperator + "\"" + EXE + "\" hook --agent gemini --gate post-edit");
        assertThat(settings.at("/hooks/AfterAgent/0/hooks/0/timeout").asInt()).isEqualTo(600_000);
        assertThat(settings.at("/hooks").has("BeforeTool")).isFalse(); // a git project: no pre-edit by default
        assertThat(projectRoot.resolve(".javaguard/.gitignore")).isRegularFile();
        assertThat(stdout()).contains("wrote Gemini CLI hooks");
    }

    @Test
    void installCodexWritesHooksJsonAndCommandWindowsOnWindows() throws Exception {
        assertThat(install("codex")).isZero();

        JsonNode hook = read(codexFile()).at("/hooks/PostToolUse/0");
        assertThat(hook.get("matcher").asText()).isEqualTo("^apply_patch$");
        assertThat(hook.at("/hooks/0/timeout").asInt()).isEqualTo(30);
        String command = hook.at("/hooks/0/command").asText();
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            assertThat(command).isEqualTo("javaguard hook --agent codex --gate post-edit");
            // PowerShell runs commandWindows: a quoted program path needs the call operator.
            assertThat(hook.at("/hooks/0/commandWindows").asText()).isEqualTo("& \"" + EXE + "\" hook --agent codex --gate post-edit");
        } else {
            assertThat(command).isEqualTo("\"" + EXE + "\" hook --agent codex --gate post-edit");
        }
        assertThat(read(codexFile()).at("/hooks/Stop/0/hooks/0/timeout").asInt()).isEqualTo(600);
        assertThat(read(codexFile()).at("/hooks").has("PreToolUse")).isTrue(); // no git repository: baselines are needed
    }

    @Test
    void installIsIdempotentAndMergesIntoAnExistingFileWithABackup() throws Exception {
        Files.createDirectories(geminiFile().getParent());
        Files.writeString(geminiFile(), "{\"theme\":\"dark\"}");

        assertThat(install("gemini", "--pre-edit", "never")).isZero();
        String first = Files.readString(geminiFile());
        clearOutput();
        assertThat(install("gemini", "--pre-edit", "never")).isZero();

        assertThat(Files.readString(geminiFile())).isEqualTo(first);
        assertThat(read(geminiFile()).get("theme").asText()).isEqualTo("dark");
        assertThat(stdout()).contains("already up to date");
        try (var files = Files.list(geminiFile().getParent())) {
            assertThat(files.filter(p -> p.getFileName().toString().contains("javaguard-backup")).count()).isEqualTo(1);
        }
    }

    @Test
    void installAllConfiguresTheThreeAgentsAndAnUnparseableFileIsLeftAlone() throws Exception {
        assertThat(install("all", "--pre-edit", "never")).isZero();
        assertThat(projectRoot.resolve(".claude/settings.json")).isRegularFile();
        assertThat(geminiFile()).isRegularFile();
        assertThat(codexFile()).isRegularFile();
        assertThat(stdout()).containsOnlyOnce("Next: run 'javaguard warmup'");

        Files.writeString(codexFile(), "not json");
        clearOutput();
        assertThat(install("codex")).isEqualTo(3);
        assertThat(codexFile()).hasContent("not json");
    }

    @Test
    void userScopeWritesIntoTheHomeDirectory() throws Exception {
        assertThat(install("gemini", "--scope", "user")).isZero();

        assertThat(userHome.resolve(".gemini/settings.json")).isRegularFile();
        assertThat(geminiFile()).doesNotExist();
    }

    @Test
    void uninstallRemovesOnlyJavaGuardEntries() throws Exception {
        Files.createDirectories(codexFile().getParent());
        String foreign = "{\"hooks\":{\"Stop\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"notify-me\"}]}]}}";
        Files.writeString(codexFile(), foreign);
        assertThat(install("codex")).isZero();

        assertThat(run("uninstall", "--agent", "codex")).isZero();

        assertThat(read(codexFile())).isEqualTo(JSON.readTree(foreign));
        assertThat(run("uninstall", "--agent", "gemini")).isZero();
        assertThat(stdout()).contains("does not exist; nothing to remove");
    }

    @Test
    void doctorSaysNothingAboutAgentsTheProjectDoesNotUse() throws Exception {
        createMavenProject();

        assertThat(doctorTable()).doesNotContain("Gemini CLI hooks").doesNotContain("Codex hooks");
    }

    @Test
    void doctorReportsInstalledAndMissingHooksForGeminiAndCodex() throws Exception {
        createMavenProject();
        Files.createDirectories(projectRoot.resolve(".gemini"));
        assertThat(doctorTable()).contains("[WARN] Gemini CLI hooks").contains("javaguard install --agent gemini");

        assertThat(install("gemini")).isZero();
        assertThat(install("codex")).isZero();

        assertThat(doctorTable()).contains("[OK  ] Gemini CLI hooks").contains("[OK  ] Codex hooks");
    }

    @Test
    void doctorWarnsAboutGeminiHooksWrittenWithoutTheCallOperatorOnWindows() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        createMavenProject();
        Files.createDirectories(geminiFile().getParent());
        Files.writeString(geminiFile(), "{\"hooks\":{\"AfterTool\":[{\"matcher\":\"write_file|replace\",\"hooks\":[{\"name\":\"javaguard-post-edit\","
                + "\"type\":\"command\",\"command\":\"\\\"" + EXE + "\\\" hook --agent gemini --gate post-edit\",\"timeout\":30000}]}]}}");

        assertThat(doctorTable()).contains("[WARN] Gemini CLI hooks").contains("must start with '& '");

        assertThat(install("gemini")).isZero();

        assertThat(doctorTable()).contains("[OK  ] Gemini CLI hooks").doesNotContain("must start with '& '");
    }

    @Test
    void doctorWarnsWhenCodexHooksAreSwitchedOff() throws Exception {
        createMavenProject();
        assertThat(install("codex")).isZero();
        Files.writeString(projectRoot.resolve(".codex/config.toml"), "[features]\nhooks = false\n");

        assertThat(doctorTable()).contains("[WARN] Codex feature flag").contains("hooks = true");

        Files.writeString(projectRoot.resolve(".codex/config.toml"), "[features]\nhooks = true\n");
        assertThat(doctorTable()).doesNotContain("Codex feature flag");
    }
}
