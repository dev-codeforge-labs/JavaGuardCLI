package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.build.BuildResult;
import com.devmanchego.javaguard.build.BuildRunner;
import com.devmanchego.javaguard.change.ChangeTracker;
import com.devmanchego.javaguard.change.GitClient;
import com.devmanchego.javaguard.config.ConfigLoader;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.model.AgentKind;
import com.devmanchego.javaguard.model.ChangeType;
import com.devmanchego.javaguard.model.FileChange;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.HookEvent;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.project.ReactorModules;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import com.devmanchego.javaguard.testing.TestSelection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 11: "API rule names reactor dependents" (specification &sect;11.5), on a copy of the real
 * {@code maven-multimodule} fixture where module-b depends on module-a, directly and through the end-of-turn gate.
 */
class ApiCompatibilityAnalyzerTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/maven-multimodule").toAbsolutePath().normalize();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC);
    private static final String FOO = "module-a/src/main/java/com/acme/modulea/Foo.java";
    private static final String BAR = "module-b/src/main/java/com/acme/moduleb/Bar.java";

    @TempDir
    Path home;

    @TempDir
    Path root;

    private JavaGuardPaths paths;
    private String originalFoo;
    private String originalBar;

    @BeforeEach
    void copyFixture() throws IOException {
        try (Stream<Path> walk = Files.walk(FIXTURE)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path target = root.resolve(FIXTURE.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else if (!source.toString().replace('\\', '/').contains("/target/")) {
                    Files.copy(source, target);
                }
            }
        }
        paths = new JavaGuardPaths(root);
        paths.ensureLayout();
        originalFoo = Files.readString(root.resolve(FOO));
        originalBar = Files.readString(root.resolve(BAR));
    }

    private JavaGuardConfig config(String yaml) throws Exception {
        if (!yaml.isEmpty()) {
            Files.writeString(root.resolve("javaguard.yml"), yaml);
        }
        return ConfigLoader.load(ConfigSources.standard(root, Optional.empty(), home, Map.of())).config();
    }

    private List<Finding> analyze(JavaGuardConfig config, Map<String, String> baselines, String... changed) {
        List<Path> files = java.util.Arrays.stream(changed).map(root::resolve).toList();
        DeepAnalyzer.Context context = new DeepAnalyzer.Context(files, ReactorModules.load(root), Duration.ofMinutes(1),
                file -> Optional.ofNullable(baselines.get(root.relativize(file).toString().replace('\\', '/'))), config, paths);
        return new ApiCompatibilityAnalyzer().analyze(context);
    }

    private void write(String relative, String content) throws IOException {
        Files.writeString(root.resolve(relative), content, StandardCharsets.UTF_8);
    }

    @Test
    void aRemovedPublicMethodNamesTheModulesThatDependOnIt() throws Exception {
        write(FOO, originalFoo.replace("    public int value() {\n        return 42;\n    }\n", ""));

        List<Finding> findings = analyze(config(""), Map.of(FOO, originalFoo), FOO);

        assertThat(findings).hasSize(1);
        Finding f = findings.get(0);
        assertThat(f.ruleId()).isEqualTo("JG-API-001");
        assertThat(f.severity()).isEqualTo(Severity.MAJOR);
        assertThat(f.file()).contains(Path.of(FOO));
        assertThat(f.message()).contains("Public API removed: value() in Foo").contains("Dependent modules that may break: module-b");
    }

    @Test
    void aChangedSignatureSaysWhatItWasAndWhatItIsNow() throws Exception {
        write(FOO, originalFoo.replace("public int value()", "public long value()"));

        List<Finding> findings = analyze(config(""), Map.of(FOO, originalFoo), FOO);

        assertThat(findings).singleElement().satisfies(f -> assertThat(f.message())
                .contains("Public API changed: value() in Foo").contains("int value()").contains("long value()")
                .contains("module-b"));
    }

    @Test
    void aBodyOnlyChangeAndANewMethodAreNotApiChanges() throws Exception {
        write(FOO, originalFoo.replace("return 42;", "return 43;").replace("    public int value() {",
                "    public int extra() {\n        return 1;\n    }\n\n    public int value() {"));

        assertThat(analyze(config(""), Map.of(FOO, originalFoo), FOO)).isEmpty();
    }

    @Test
    void aModuleNobodyDependsOnHasNoConsumersToProtect() throws Exception {
        write(BAR, originalBar.replace("    public int doubled() {\n        return new Foo().value() * 2;\n    }\n", ""));

        assertThat(analyze(config(""), Map.of(BAR, originalBar), BAR)).isEmpty();
    }

    @Test
    void aListedPublicPackageIsProtectedEvenWithoutDependents() throws Exception {
        write(BAR, originalBar.replace("    public int doubled() {\n        return new Foo().value() * 2;\n    }\n", ""));

        List<Finding> findings = analyze(config("api:\n  publicPackages: [\"com.acme.moduleb.**\"]\n"), Map.of(BAR, originalBar), BAR);

        assertThat(findings).singleElement().satisfies(f -> assertThat(f.message())
                .contains("Public API removed: doubled() in Bar").contains("No module of this build depends on it; external consumers may."));
    }

    @Test
    void aPackageOutsideTheListedOnesIsNotProtectedWithoutDependents() throws Exception {
        write(BAR, originalBar.replace("    public int doubled() {\n        return new Foo().value() * 2;\n    }\n", ""));

        assertThat(analyze(config("api:\n  publicPackages: [\"com.other.**\"]\n"), Map.of(BAR, originalBar), BAR)).isEmpty();
    }

    @Test
    void aNewFileHasNoBaselineAndRemovesNothing() throws Exception {
        assertThat(analyze(config(""), Map.of(), FOO)).isEmpty();
    }

    @Test
    void aDeletedPublicClassRemovesItsWholeApi() throws Exception {
        Files.delete(root.resolve(FOO));

        List<Finding> findings = analyze(config(""), Map.of(FOO, originalFoo), FOO);

        assertThat(findings).extracting(Finding::message).anyMatch(m -> m.contains("Public API removed: Foo"))
                .anyMatch(m -> m.contains("value() in Foo"));
    }

    @Test
    void testSourcesAndBuildFilesAreIgnored() throws Exception {
        String test = "module-a/src/test/java/com/acme/modulea/FooTest.java";
        Files.createDirectories(root.resolve(test).getParent());
        write(test, "package com.acme.modulea;\npublic class FooTest {}\n");

        assertThat(analyze(config(""), Map.of(test, "package com.acme.modulea;\npublic class FooTest { public void x() {} }\n",
                "module-a/pom.xml", "<project/>"), test, "module-a/pom.xml")).isEmpty();
    }

    // --- through the end-of-turn gate ------------------------------------------------------------------

    private GateResult endOfTurnAfterEditingFoo(JavaGuardConfig config) throws Exception {
        ChangeTracker tracker = new ChangeTracker(paths, file -> Optional.of(originalFoo.getBytes(StandardCharsets.UTF_8)), CLOCK);
        Path foo = root.resolve(FOO);
        HookEvent edit = new HookEvent(AgentKind.CLAUDE, GateKind.POST_EDIT, "s1", root, Optional.empty(), "Edit",
                List.of(FileChange.rewrite(foo, ChangeType.UPDATE)), false, Optional.empty(), CLOCK.instant());
        tracker.recordAfterEdit(edit);
        BuildRunner passes = new BuildRunner() {
            @Override
            public BuildResult testCompile(Collection<Path> moduleRoots, Duration timeout) {
                return new BuildResult(true, true, 0, List.of(), List.of(), Optional.empty(), Duration.ZERO);
            }

            @Override
            public BuildResult runTests(TestSelection selection, Duration timeout) {
                return new BuildResult(true, true, 0, List.of(), List.of(), Optional.empty(), Duration.ZERO);
            }
        };
        return new EndOfTurnGate(paths, config, tracker, () -> Optional.of(Set.of(foo)),
                file -> Optional.of(originalFoo.getBytes(StandardCharsets.UTF_8)), reactor -> Optional.of(passes), (m, r) -> { },
                List.of(new ApiCompatibilityAnalyzer()), CLOCK)
                .run(new HookEvent(AgentKind.CLAUDE, GateKind.END_OF_TURN, "s1", root, Optional.empty(), "", List.of(), false,
                        Optional.empty(), CLOCK.instant()));
    }

    @Test
    void theEndOfTurnGateAdvisesAboutAnApiChangeAndNamesTheDependents() throws Exception {
        write(FOO, originalFoo.replace("public int value()", "public int val()"));

        GateResult result = endOfTurnAfterEditingFoo(config(""));

        assertThat(result.verdict()).isEqualTo(Verdict.ADVISE);
        assertThat(result.blocking()).as("the dependents are rebuilt by the compile step; this rule is advisory").isEmpty();
        assertThat(result.advisory()).extracting(Finding::ruleId).contains("JG-API-001");
        assertThat(result.advisory().get(0).message()).contains("module-b");
    }

    @Test
    void theApiRuleCanBeSwitchedOffInTheConfiguration() throws Exception {
        write(FOO, originalFoo.replace("public int value()", "public int val()"));

        GateResult result = endOfTurnAfterEditingFoo(config("rules:\n  JG-API-001: { enabled: false }\n"));

        assertThat(result.advisory()).extracting(Finding::ruleId).doesNotContain("JG-API-001");
    }

    @Test
    void anApiRuleSeverityOverrideAppliesToTheDeepFindings() throws Exception {
        write(FOO, originalFoo.replace("public int value()", "public int val()"));

        GateResult result = endOfTurnAfterEditingFoo(config("rules:\n  JG-API-001: { severity: BLOCKER }\n"));

        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.blocking()).extracting(Finding::ruleId).contains("JG-API-001");
    }
}
