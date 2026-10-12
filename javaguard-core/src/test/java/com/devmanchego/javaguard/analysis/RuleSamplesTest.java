package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Finding;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 5: "parameterized rule test harness over sample dirs". Every
 * subdirectory of {@code src/test/resources/rules/} is a rule id; every file
 * under its {@code bad/} must trigger that rule, every file under its
 * {@code good/} (including the known false-positive shapes) must not.
 *
 * <p>This is the actual specification of each rule's precision: a sample
 * added here fixes behavior the way a golden file does elsewhere in this
 * project. A rule with no {@code good/} samples is not accepted (plan Phase 5).
 */
class RuleSamplesTest {

    private static final Path RULES_DIR = Path.of("src/test/resources/rules");
    private static final AstCheckEngine ENGINE = new AstCheckEngine();

    @TestFactory
    Stream<DynamicNode> ruleSamples() throws IOException {
        if (!Files.isDirectory(RULES_DIR)) {
            return Stream.empty();
        }
        try (Stream<Path> dirs = Files.list(RULES_DIR)) {
            return dirs.filter(Files::isDirectory).sorted().<DynamicNode>map(this::forRule).toList().stream();
        }
    }

    private DynamicContainer forRule(Path ruleDir) {
        String ruleId = ruleDir.getFileName().toString();
        List<DynamicTest> tests = new ArrayList<>();
        collect(tests, ruleDir.resolve("bad"), ruleId, true);
        collect(tests, ruleDir.resolve("good"), ruleId, false);
        if (!Files.isDirectory(ruleDir.resolve("good"))) {
            tests.add(DynamicTest.dynamicTest("MISSING good/ samples", () ->
                    org.junit.jupiter.api.Assertions.fail(
                            ruleId + " has no good/ samples (plan Phase 5: a rule without one is not accepted)")));
        }
        return DynamicContainer.dynamicContainer(ruleId, tests);
    }

    private void collect(List<DynamicTest> tests, Path dir, String ruleId, boolean shouldFire) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".java")).sorted().forEach(file ->
                    tests.add(DynamicTest.dynamicTest((shouldFire ? "bad/" : "good/") + file.getFileName(),
                            () -> assertSample(file, ruleId, shouldFire))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void assertSample(Path file, String ruleId, boolean shouldFire) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String relativePath = "src/main/java/" + file.getFileName();
        List<Finding> findings = ENGINE.analyze(relativePath, content);
        boolean fires = findings.stream().anyMatch(f -> f.ruleId().equals(ruleId));
        if (shouldFire) {
            assertThat(fires).as("%s should trigger %s but got: %s", file, ruleId, findings).isTrue();
        } else {
            assertThat(fires).as("%s should NOT trigger %s (known false-positive shape) but got: %s",
                    file, ruleId, findings).isFalse();
        }
    }
}
