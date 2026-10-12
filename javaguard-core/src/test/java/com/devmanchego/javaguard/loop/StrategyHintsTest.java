package com.devmanchego.javaguard.loop;

import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.GateKind;
import com.devmanchego.javaguard.model.Severity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 12: the strategy hint texts (specification &sect;13.3, catalog &sect;6). */
class StrategyHintsTest {

    private static Finding finding(Category category, String message, Map<String, String> detail) {
        return new Finding("RULE", "t", category, Severity.CRITICAL, "x", Optional.of(Path.of("src/A.java")), 7, 1, 7, message,
                "fix", Optional.empty(), detail, Fingerprints.of("RULE", "src/A.java", message));
    }

    private static List<RunRecord> runs(String... hashesOfA) {
        java.util.ArrayList<RunRecord> runs = new java.util.ArrayList<>();
        for (int i = 0; i < hashesOfA.length; i++) {
            runs.add(new RunRecord(i + 1, GateKind.END_OF_TURN, true, List.of("x"), 1, Map.of("src/A.java", hashesOfA[i]), false));
        }
        return runs;
    }

    @Test
    void levelOneFollowsTheSpecifiedShape() {
        String hint = StrategyHints.guidance(1, "repeat", 3,
                List.of(finding(Category.COMPILATION, "cannot find symbol", Map.of("symbol", "method value()", "location", "class Foo"))),
                runs("1", "2", "3"));

        assertThat(hint).isEqualTo("You have attempted this 3 times with the same result. Your previous attempts changed "
                + "src/A.java (2 times). Stop repeating that approach: re-read src/A.java; check 'method value()' in 'class Foo';"
                + " consider: Check the exact name and signature of method value() in class Foo. Read the declaring class instead"
                + " of guessing; verify imports and the dependency that provides it.");
    }

    @Test
    void eachCategoryOfTheCatalogGetsItsHint() {
        assertThat(StrategyHints.categoryHint(List.of(finding(Category.COMPILATION, "incompatible types: String cannot be converted to int", Map.of()))))
                .contains("Read the declared types involved; do not cast blindly.");
        assertThat(StrategyHints.categoryHint(List.of(finding(Category.TEST_FAILURE, "x", Map.of())))).hasValueSatisfying(h ->
                assertThat(h).startsWith("Read the failing test and the production method it calls."));
        assertThat(StrategyHints.categoryHint(List.of(finding(Category.DEPENDENCY, "x", Map.of())))).hasValueSatisfying(h ->
                assertThat(h).startsWith("Verify the artifact coordinates exist"));
        assertThat(StrategyHints.categoryHint(List.of(finding(Category.SECURITY, "x", Map.of())))).hasValueSatisfying(h ->
                assertThat(h).startsWith("Apply the remediation literally"));
        assertThat(StrategyHints.categoryHint(List.of(finding(Category.RELIABILITY, "x", Map.of())))).isPresent();
        assertThat(StrategyHints.categoryHint(List.of(finding(Category.PERFORMANCE, "x", Map.of())))).isEmpty();
    }

    @Test
    void theFirstFindingWithAHintDecides() {
        assertThat(StrategyHints.categoryHint(List.of(finding(Category.PERFORMANCE, "x", Map.of()),
                finding(Category.TEST_FAILURE, "y", Map.of())))).hasValueSatisfying(h -> assertThat(h).startsWith("Read the failing test"));
    }

    @Test
    void theOpeningSentenceSaysWhatTheDetectorSaw() {
        List<Finding> blocking = List.of(finding(Category.PERFORMANCE, "x", Map.of()));
        assertThat(StrategyHints.guidance(1, "oscillation", 4, blocking, runs("1", "2", "1", "2")))
                .startsWith("You have attempted this 4 times and returned to a version that was already rejected.");
        assertThat(StrategyHints.guidance(1, "no-progress", 5, blocking, runs("1", "2", "3", "4", "5")))
                .startsWith("You have attempted this 5 times without reducing the number of blocking problems.");
        assertThat(StrategyHints.guidance(1, "stop-guard", 3, blocking, runs("1", "1", "1")))
                .startsWith("You have attempted this 3 times with the same result. Your previous attempts did not change any file");
    }

    @Test
    void levelTwoAnnouncesTheSecondOpinion() {
        assertThat(StrategyHints.guidance(2, "repeat", 6, List.of(finding(Category.SECURITY, "x", Map.of())), runs("1", "2")))
                .endsWith("A second opinion has been requested.");
    }

    @Test
    void levelThreeTellsTheAgentToStopAndHandOver() {
        assertThat(StrategyHints.guidance(3, "repeat", 6, List.of(), List.of()))
                .startsWith("STOP. Do not edit further. Tell the user that JavaGuard halted the session")
                .contains("6 attempts");
    }

    @Test
    void aTestToCheckIsNamedForTestFailures() {
        String hint = StrategyHints.guidance(1, "repeat", 3,
                List.of(finding(Category.TEST_FAILURE, "failed", Map.of("test", "com.acme.FooTest#adds"))), runs("1", "2", "3"));

        assertThat(hint).contains("check the test com.acme.FooTest#adds;");
    }
}
