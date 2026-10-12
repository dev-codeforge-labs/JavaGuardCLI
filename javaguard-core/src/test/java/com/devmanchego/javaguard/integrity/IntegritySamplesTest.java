package com.devmanchego.javaguard.integrity;

import com.devmanchego.javaguard.model.Finding;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 10: the parameterized pair harness. {@code src/test/resources/integrity/<rule>/<case>/} holds a
 * {@code baseline.*} and a {@code current.*} file (Java, or {@code .xml} for a {@code pom.xml}); a case named
 * {@code bad-*} must produce the rule's finding, a case named {@code good-*} must not. A missing baseline is an
 * empty one (a new file); a missing current file is a deleted one. These pairs are the specification of the
 * diff rules' precision, including the shapes that must not fire: a pure rename, a move into a nested class, a
 * refactor that keeps the assertion count.
 */
class IntegritySamplesTest {

    private static final Path SAMPLES = Path.of("src/test/resources/integrity");
    private static final String TEST_PATH = "src/test/java/com/acme/SampleTest.java";
    private static final TestIntegrityEngine ENGINE = new TestIntegrityEngine();

    @TestFactory
    Stream<DynamicNode> pairs() throws IOException {
        try (Stream<Path> rules = Files.list(SAMPLES)) {
            return rules.filter(Files::isDirectory).sorted().<DynamicNode>map(this::forRule).toList().stream();
        }
    }

    private DynamicContainer forRule(Path ruleDir) {
        String ruleId = ruleDir.getFileName().toString();
        List<DynamicTest> tests = new ArrayList<>();
        boolean hasGood = false;
        try (Stream<Path> cases = Files.list(ruleDir)) {
            for (Path caseDir : cases.filter(Files::isDirectory).sorted().toList()) {
                String name = caseDir.getFileName().toString();
                boolean shouldFire = name.startsWith("bad-");
                hasGood |= name.startsWith("good-");
                tests.add(DynamicTest.dynamicTest(name, () -> assertCase(caseDir, ruleId, shouldFire)));
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        if (!hasGood) {
            tests.add(DynamicTest.dynamicTest("MISSING good- cases", () ->
                    org.junit.jupiter.api.Assertions.fail(ruleId + " has no good- cases (a rule without one is not accepted)")));
        }
        return DynamicContainer.dynamicContainer(ruleId, tests);
    }

    private static void assertCase(Path caseDir, String ruleId, boolean shouldFire) throws IOException {
        Path baselineFile = find(caseDir, "baseline");
        Path currentFile = find(caseDir, "current");
        String baseline = baselineFile == null ? "" : Files.readString(baselineFile, StandardCharsets.UTF_8);
        boolean xml = (baselineFile != null && baselineFile.toString().endsWith(".xml"))
                || (currentFile != null && currentFile.toString().endsWith(".xml"));

        List<Finding> findings;
        if (xml) {
            findings = ENGINE.checkBuildFile("pom.xml", baseline, Files.readString(currentFile, StandardCharsets.UTF_8));
        } else if (currentFile == null) {
            findings = ENGINE.checkDeleted(TEST_PATH, baseline);
        } else {
            findings = ENGINE.checkJava(TEST_PATH, baseline, Files.readString(currentFile, StandardCharsets.UTF_8));
        }
        boolean fires = findings.stream().anyMatch(f -> f.ruleId().equals(ruleId));
        if (shouldFire) {
            assertThat(fires).as("%s should produce %s but got: %s", caseDir.getFileName(), ruleId, findings).isTrue();
        } else {
            assertThat(fires).as("%s should NOT produce %s but got: %s", caseDir.getFileName(), ruleId, findings).isFalse();
        }
    }

    private static Path find(Path dir, String stem) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith(stem + ".")).findFirst().orElse(null);
        }
    }
}
