package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.install.ClaudeHookConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 7: {@code install} merge preserves foreign hooks and is idempotent; {@code uninstall} removes only JavaGuard entries. */
class InstallUninstallCommandTest extends CommandTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EXE = "C:/tools/javaguard/javaguard.exe";

    private Path projectSettings() {
        return projectRoot.resolve(".claude/settings.json");
    }

    private JsonNode settings(Path file) throws IOException {
        return JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
    }

    private int install(String... extra) {
        String[] args = new String[extra.length + 5];
        args[0] = "install";
        args[1] = "--agent";
        args[2] = "claude";
        args[3] = "--command";
        args[4] = EXE;
        System.arraycopy(extra, 0, args, 5, extra.length);
        return run(args);
    }

    private long backups(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().contains("javaguard-backup")).count();
        }
    }

    @Test
    void installsThePostEditHookWithTheContractCommandLine() throws Exception {
        Files.createDirectories(projectRoot.resolve(".git")); // a git project: no PreToolUse by default

        assertThat(install()).isZero();

        JsonNode hook = settings(projectSettings()).at("/hooks/PostToolUse/0");
        assertThat(hook.get("matcher").asText()).isEqualTo("Edit|Write|MultiEdit");
        assertThat(hook.at("/hooks/0/type").asText()).isEqualTo("command");
        assertThat(hook.at("/hooks/0/command").asText())
                .isEqualTo("\"" + EXE + "\" hook --agent claude --gate post-edit");
        assertThat(hook.at("/hooks/0/timeout").asInt()).isEqualTo(30);
        assertThat(settings(projectSettings()).at("/hooks").has("PreToolUse")).isFalse();
    }

    @Test
    void createsTheStateDirectoryWithSelfIgnoreAndACommentedConfigThatLoads() throws Exception {
        assertThat(install()).isZero();

        assertThat(projectRoot.resolve(".javaguard/.gitignore")).hasContent("*\n");
        assertThat(projectRoot.resolve("javaguard.yml")).isRegularFile();
        assertThat(ConfigLoader.load(ConfigSources.standard(projectRoot, Optional.empty(), userHome, Map.of()))
                .warnings()).isEmpty();
    }

    @Test
    void doesNotOverwriteAnExistingConfig() throws Exception {
        Files.writeString(projectRoot.resolve("javaguard.yml"), "exclude: [\"a/**\"]\n");

        assertThat(install()).isZero();

        assertThat(projectRoot.resolve("javaguard.yml")).hasContent("exclude: [\"a/**\"]\n");
    }

    @Test
    void mergesIntoExistingSettingsKeepingForeignHooksAndBacksUpFirst() throws Exception {
        Files.createDirectories(projectRoot.resolve(".claude"));
        String original = "{\"model\":\"opus\",\"hooks\":{\"PostToolUse\":[{\"matcher\":\"Bash\","
                + "\"hooks\":[{\"type\":\"command\",\"command\":\"echo hi\"}]}]}}";
        Files.writeString(projectSettings(), original);

        assertThat(install()).isZero();

        JsonNode updated = settings(projectSettings());
        assertThat(updated.get("model").asText()).isEqualTo("opus");
        assertThat(updated.at("/hooks/PostToolUse")).hasSize(2);
        assertThat(updated.at("/hooks/PostToolUse/0/hooks/0/command").asText()).isEqualTo("echo hi");
        assertThat(backups(projectRoot.resolve(".claude"))).isEqualTo(1);
        try (Stream<Path> files = Files.list(projectRoot.resolve(".claude"))) {
            Path backup = files.filter(p -> p.getFileName().toString().contains("javaguard-backup")).findFirst().orElseThrow();
            assertThat(backup).hasContent(original);
        }
    }

    @Test
    void installingTwiceChangesNothingAndMakesNoSecondBackup() throws Exception {
        assertThat(install()).isZero();
        String afterFirst = Files.readString(projectSettings());
        clearOutput();

        assertThat(install()).isZero();

        assertThat(Files.readString(projectSettings())).isEqualTo(afterFirst);
        assertThat(backups(projectRoot.resolve(".claude"))).isZero();
        assertThat(stdout()).contains("already up to date");
    }

    @Test
    void invalidJsonIsNeverOverwritten() throws Exception {
        Files.createDirectories(projectRoot.resolve(".claude"));
        Files.writeString(projectSettings(), "{ \"hooks\": ");

        int exit = install();

        assertThat(exit).isEqualTo(3);
        assertThat(stderr()).contains("not valid JSON").contains("not modifying");
        assertThat(projectSettings()).hasContent("{ \"hooks\": ");
    }

    @Test
    void dryRunPrintsTheResultAndWritesNothing() {
        int exit = install("--dry-run");

        assertThat(exit).isZero();
        assertThat(stdout()).contains("\"PostToolUse\"").contains("hook --agent claude --gate post-edit");
        assertThat(projectSettings()).doesNotExist();
        assertThat(projectRoot.resolve(".javaguard")).doesNotExist();
        assertThat(projectRoot.resolve("javaguard.yml")).doesNotExist();
    }

    @Test
    void preEditAutoIsInstalledForProjectsWithoutGit() throws Exception {
        assertThat(install()).isZero();

        assertThat(settings(projectSettings()).at("/hooks/PreToolUse/0/hooks/0/command").asText())
                .endsWith("hook --agent claude --gate pre-edit");
    }

    @Test
    void preEditAlwaysAndNeverAreHonoredRegardlessOfGit() throws Exception {
        Files.createDirectories(projectRoot.resolve(".git"));
        assertThat(install("--pre-edit", "always")).isZero();
        assertThat(ClaudeHookConfig.installedGates(settings(projectSettings())))
                .contains(ClaudeHookConfig.Gate.PRE_EDIT, ClaudeHookConfig.Gate.POST_EDIT);

        assertThat(install("--pre-edit", "never")).isZero();
        assertThat(ClaudeHookConfig.installedGates(settings(projectSettings())))
                .containsExactlyInAnyOrder(ClaudeHookConfig.Gate.POST_EDIT, ClaudeHookConfig.Gate.END_OF_TURN,
                        ClaudeHookConfig.Gate.TURN_START);
    }

    @Test
    void installsTheTurnStartHookSilentlyAndWithoutAMatcher() throws Exception {
        assertThat(install()).isZero();

        JsonNode group = settings(projectSettings()).at("/hooks/UserPromptSubmit/0");
        assertThat(group.has("matcher")).isFalse();
        assertThat(group.at("/hooks/0/command").asText()).isEqualTo("\"" + EXE + "\" hook --agent claude --gate turn-start");
        assertThat(group.at("/hooks/0/timeout").asInt()).isEqualTo(10);
        assertThat(group.at("/hooks/0").has("statusMessage")).as("a status line on every prompt would be noise").isFalse();
    }

    @Test
    void installsTheStopHookForTheEndOfTurnGate() throws Exception {
        assertThat(install()).isZero();

        JsonNode stop = settings(projectSettings()).at("/hooks/Stop/0");
        assertThat(stop.has("matcher")).as("Stop hooks take no matcher").isFalse();
        assertThat(stop.at("/hooks/0/command").asText())
                .isEqualTo("\"" + EXE + "\" hook --agent claude --gate end-of-turn");
        assertThat(stop.at("/hooks/0/timeout").asInt()).isEqualTo(600);
    }

    @Test
    void userScopeWritesUnderTheUserHome() {
        assertThat(install("--scope", "user")).isZero();

        assertThat(userHome.resolve(".claude/settings.json")).isRegularFile();
        assertThat(projectSettings()).doesNotExist();
    }

    @Test
    void invalidOptionValuesAreUsageErrors() {
        assertThat(run("install", "--agent", "copilot")).isEqualTo(2);
        assertThat(run("install", "--agent", "claude", "--scope", "galaxy")).isEqualTo(2);
        assertThat(run("install", "--agent", "claude", "--pre-edit", "sometimes")).isEqualTo(2);
    }

    @Test
    void uninstallRemovesOnlyJavaGuardEntriesAndKeepsTheStateDirectory() throws Exception {
        Files.createDirectories(projectRoot.resolve(".claude"));
        String foreign = "{\"hooks\":{\"PostToolUse\":[{\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"echo hi\"}]}]}}";
        Files.writeString(projectSettings(), foreign);
        assertThat(install()).isZero();

        int exit = run("uninstall", "--agent", "claude");

        assertThat(exit).isZero();
        assertThat(settings(projectSettings())).isEqualTo(JSON.readTree(foreign));
        assertThat(projectRoot.resolve(".javaguard")).isDirectory();
        assertThat(projectRoot.resolve("javaguard.yml")).isRegularFile();
    }

    @Test
    void uninstallWithoutASettingsFileIsANoOp() {
        assertThat(run("uninstall", "--agent", "claude")).isZero();
        assertThat(stdout()).contains("nothing to remove");
    }

    @Test
    void uninstallLeavesAnUnparseableFileAlone() throws Exception {
        Files.createDirectories(projectRoot.resolve(".claude"));
        Files.writeString(projectSettings(), "not json");

        assertThat(run("uninstall", "--agent", "claude")).isEqualTo(3);
        assertThat(projectSettings()).hasContent("not json");
    }
}
