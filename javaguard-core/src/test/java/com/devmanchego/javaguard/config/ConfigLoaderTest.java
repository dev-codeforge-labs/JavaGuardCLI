package com.devmanchego.javaguard.config;

import com.devmanchego.javaguard.config.JavaGuardConfig.Policy;
import com.devmanchego.javaguard.config.JavaGuardConfig.TestScope;
import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Specification section 18: defaults, precedence, unknown keys, validation. */
class ConfigLoaderTest {

    @TempDir
    Path dir;

    private Path write(String name, String yaml) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return file;
    }

    private static ConfigSources sources(Path user, Path project, Map<String, String> env, Map<String, String> cli) {
        return new ConfigSources(Optional.ofNullable(user), Optional.ofNullable(project), false, env, cli);
    }

    private LoadedConfig loadProject(String yaml) throws Exception {
        return ConfigLoader.load(sources(null, write("javaguard.yml", yaml), Map.of(), Map.of()));
    }

    // --- defaults ---------------------------------------------------------

    @Test
    void defaultsMatchTheSpecification() throws ConfigException {
        LoadedConfig loaded = ConfigLoader.load(ConfigSources.defaultsOnly());
        JavaGuardConfig c = loaded.config();

        assertThat(loaded.warnings()).isEmpty();
        assertThat(c.exclude()).containsExactly("**/generated/**", "**/target/**", "**/build/**");
        assertThat(c.build().tool()).isEqualTo(JavaGuardConfig.BuildTool.AUTO);
        assertThat(c.build().maven().useDaemon()).isTrue();
        assertThat(c.fastGate().budgetMillis()).isEqualTo(3000);
        assertThat(c.fastGate().compileErrors()).isEqualTo(Policy.ADVISE);
        assertThat(c.fullGate().budgetSeconds()).isEqualTo(540);
        assertThat(c.fullGate().maxConsecutiveBlocks()).isEqualTo(3);
        assertThat(c.tests().scope()).isEqualTo(TestScope.AFFECTED);
        assertThat(c.tests().maxClasses()).isEqualTo(50);
        assertThat(c.tests().unrelatedFailures()).isEqualTo(Policy.ADVISE);
        assertThat(c.errorprone().enabled()).isTrue();
        assertThat(c.rules()).isEmpty();
        assertThat(c.deps().osv().timeoutSeconds()).isEqualTo(5);
        assertThat(c.report().maxFindings()).isEqualTo(15);
        assertThat(c.report().maxChars()).isEqualTo(6000);
        assertThat(c.report().ascii()).isTrue();
        assertThat(c.loop().repeatThreshold()).isEqualTo(3);
        assertThat(c.loop().maxBlockedRuns()).isEqualTo(12);
        assertThat(c.handoff().copyToProjectRoot()).isTrue();
        assertThat(c.consult().enabled()).isFalse();
        assertThat(c.consult().order()).containsExactly(AgentKind.GEMINI, AgentKind.CODEX, AgentKind.CLAUDE);
        assertThat(c.consult().claude().model()).isEqualTo("claude-opus-5-5");
        assertThat(c.notifications().terminalBell()).isTrue();
        assertThat(c.notifications().ntfy().enabled()).isFalse();
        assertThat(c.state().retentionDays()).isEqualTo(7);
    }

    // --- merging and precedence ------------------------------------------

    @Test
    void projectFileOverridesOnlyTheKeysItSets() throws Exception {
        JavaGuardConfig c = loadProject("fastGate:\n  budgetMillis: 5000\n").config();

        assertThat(c.fastGate().budgetMillis()).isEqualTo(5000);
        assertThat(c.fastGate().enabled()).isTrue();
        assertThat(c.fastGate().compileErrors()).isEqualTo(Policy.ADVISE);
    }

    @Test
    void precedenceIsUserThenProjectThenEnvironmentThenCli() throws Exception {
        Path user = write("user.yml", "fastGate:\n  budgetMillis: 1000\nreport:\n  maxFindings: 7\n");
        Path project = write("javaguard.yml", "fastGate:\n  budgetMillis: 2000\n");
        Map<String, String> env = Map.of("JAVAGUARD_FASTGATE_BUDGETMILLIS", "3500");
        Map<String, String> cli = Map.of("fastGate.budgetMillis", "4000");

        assertThat(ConfigLoader.load(sources(user, null, Map.of(), Map.of())).config().fastGate().budgetMillis()).isEqualTo(1000);
        assertThat(ConfigLoader.load(sources(user, project, Map.of(), Map.of())).config().fastGate().budgetMillis()).isEqualTo(2000);
        assertThat(ConfigLoader.load(sources(user, project, env, Map.of())).config().fastGate().budgetMillis()).isEqualTo(3500);
        JavaGuardConfig all = ConfigLoader.load(sources(user, project, env, cli)).config();
        assertThat(all.fastGate().budgetMillis()).isEqualTo(4000);
        assertThat(all.report().maxFindings()).as("user-only key survives higher layers").isEqualTo(7);
    }

    @Test
    void listsAreReplacedNotConcatenated() throws Exception {
        assertThat(loadProject("exclude: [\"legacy/**\"]\n").config().exclude()).containsExactly("legacy/**");
    }

    @Test
    void nullValuesKeepTheDefault() throws Exception {
        JavaGuardConfig c = loadProject("fastGate:\n  budgetMillis: ~\nexclude: ~\n").config();

        assertThat(c.fastGate().budgetMillis()).isEqualTo(3000);
        assertThat(c.exclude()).hasSize(3);
    }

    @Test
    void enumValuesAreCaseInsensitive() throws Exception {
        JavaGuardConfig c = loadProject("tests:\n  scope: MODULE\nfastGate:\n  compileErrors: Block\n").config();

        assertThat(c.tests().scope()).isEqualTo(TestScope.MODULE);
        assertThat(c.fastGate().compileErrors()).isEqualTo(Policy.BLOCK);
    }

    @Test
    void emptyFileMeansNoOverrides() throws Exception {
        assertThat(loadProject("").config().fastGate().budgetMillis()).isEqualTo(3000);
        assertThat(loadProject("# only a comment\n").warnings()).isEmpty();
    }

    @Test
    void missingOptionalFilesAreSkipped() throws ConfigException {
        LoadedConfig loaded = ConfigLoader.load(
                sources(dir.resolve("absent-user.yml"), dir.resolve("absent-project.yml"), Map.of(), Map.of()));

        assertThat(loaded.config().fastGate().budgetMillis()).isEqualTo(3000);
    }

    @Test
    void missingExplicitConfigFileIsAnError() {
        ConfigSources explicit = new ConfigSources(Optional.empty(), Optional.of(dir.resolve("nope.yml")), true,
                Map.of(), Map.of());

        assertThatThrownBy(() -> ConfigLoader.load(explicit))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("nope.yml");
    }

    @Test
    void standardSourcesLookInHomeAndProjectRoot() {
        ConfigSources s = ConfigSources.standard(dir.resolve("proj"), Optional.empty(), dir.resolve("home"), Map.of());

        assertThat(s.userFile()).contains(dir.resolve("home").resolve(".javaguard").resolve("config.yml"));
        assertThat(s.projectFile()).contains(dir.resolve("proj").resolve("javaguard.yml"));
        assertThat(s.explicit()).isFalse();
    }

    // --- rules -------------------------------------------------------------

    @Test
    void ruleOverridesAreParsedWithOptions() throws Exception {
        JavaGuardConfig c = loadProject("""
                rules:
                  JG-PERF-005: { enabled: false }
                  JG-AI-001:
                    severity: info
                    exclude: ["**/cli/**"]
                    allow: ["**.tools.**"]
                """).config();

        assertThat(c.rule("JG-PERF-005")).hasValueSatisfying(r -> {
            assertThat(r.enabled()).contains(false);
            assertThat(r.severity()).isEmpty();
        });
        assertThat(c.rule("JG-AI-001")).hasValueSatisfying(r -> {
            assertThat(r.enabled()).isEmpty();
            assertThat(r.severity()).contains(Severity.INFO);
            assertThat(r.exclude()).containsExactly("**/cli/**");
            assertThat(r.options()).containsEntry("allow", List.of("**.tools.**"));
        });
        assertThat(c.rule("JG-SEC-001")).isEmpty();
    }

    @Test
    void invalidRuleSeverityIsReportedWithItsPath() {
        assertThatThrownBy(() -> loadProject("rules:\n  JG-SEC-001: { severity: fatal }\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("rules.JG-SEC-001.severity");
    }

    // --- unknown keys --------------------------------------------------------

    @Test
    void unknownKeysAreWarningsNotErrors() throws Exception {
        LoadedConfig loaded = loadProject("fastGate:\n  budgetMilis: 10\nbogus: true\n");

        assertThat(loaded.config().fastGate().budgetMillis()).isEqualTo(3000);
        assertThat(loaded.warnings()).anyMatch(w -> w.contains("fastGate.budgetMilis") && w.contains("javaguard.yml"));
        assertThat(loaded.warnings()).anyMatch(w -> w.contains("bogus"));
    }

    @Test
    void freeFormMapsAcceptAnyKey() throws Exception {
        LoadedConfig loaded = loadProject("""
                rules:
                  ANY-RULE: { enabled: true }
                consult:
                  commands:
                    gemini: "gemini -p {instruction}"
                """);

        assertThat(loaded.warnings()).isEmpty();
        assertThat(loaded.config().consult().commands()).containsEntry("gemini", "gemini -p {instruction}");
    }

    // --- environment -------------------------------------------------------

    @Test
    void environmentVariablesReachNestedKeys() throws ConfigException {
        Map<String, String> env = new HashMap<>();
        env.put("JAVAGUARD_DEPS_OSV_ENABLED", "false");
        env.put("JAVAGUARD_REPORT_MAXCHARS", "9000");
        env.put("JAVAGUARD_CONSULT_ORDER", "[codex, claude]");
        env.put("JAVAGUARD_CONSULT_CLAUDE_MODEL", "claude-sonnet-5");
        env.put("PATH", "/usr/bin");

        LoadedConfig loaded = ConfigLoader.load(sources(null, null, env, Map.of()));

        assertThat(loaded.config().deps().osv().enabled()).isFalse();
        assertThat(loaded.config().report().maxChars()).isEqualTo(9000);
        assertThat(loaded.config().consult().order()).containsExactly(AgentKind.CODEX, AgentKind.CLAUDE);
        assertThat(loaded.config().consult().claude().model()).isEqualTo("claude-sonnet-5");
        assertThat(loaded.warnings()).isEmpty();
    }

    @Test
    void unmatchedEnvironmentVariablesAreWarnings() throws ConfigException {
        LoadedConfig loaded = ConfigLoader.load(sources(null, null,
                Map.of("JAVAGUARD_FASTGATE_NOPE", "1", "JAVAGUARD_DISABLED", "1"), Map.of()));

        assertThat(loaded.warnings()).singleElement().asString().contains("JAVAGUARD_FASTGATE_NOPE");
    }

    // --- errors ------------------------------------------------------------

    @Test
    void invalidEnumValueNamesTheKey() {
        assertThatThrownBy(() -> loadProject("tests:\n  scope: everything\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("tests.scope");
    }

    @Test
    void wrongValueTypeNamesTheKey() {
        assertThatThrownBy(() -> loadProject("fastGate:\n  budgetMillis: soon\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("fastGate.budgetMillis");
    }

    @Test
    void malformedYamlNamesTheFile() {
        assertThatThrownBy(() -> loadProject("fastGate: [unclosed\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("javaguard.yml");
    }

    @Test
    void topLevelMustBeAMapping() {
        assertThatThrownBy(() -> loadProject("- just\n- a list\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("mapping");
    }

    @Test
    void validationReportsEveryProblemAtOnce() {
        assertThatThrownBy(() -> loadProject("""
                fastGate: { budgetMillis: 0 }
                report: { maxChars: 10 }
                handoff: { redactPatterns: ["(unclosed"] }
                consult: { order: [manual] }
                notify: { ntfy: { enabled: true, url: "" } }
                loop: { repeatThreshold: 1 }
                """))
                .isInstanceOfSatisfying(ConfigException.class, e -> assertThat(e.problems())
                        .anyMatch(p -> p.contains("fastGate.budgetMillis"))
                        .anyMatch(p -> p.contains("report.maxChars"))
                        .anyMatch(p -> p.contains("handoff.redactPatterns"))
                        .anyMatch(p -> p.contains("consult.order"))
                        .anyMatch(p -> p.contains("notify.ntfy.url"))
                        .anyMatch(p -> p.contains("loop.repeatThreshold")));
    }

    @Test
    void unknownCliOverrideIsAnError() {
        assertThatThrownBy(() -> ConfigLoader.load(sources(null, null, Map.of(), Map.of("fastGate.nope", "1"))))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("fastGate.nope");
    }
}
