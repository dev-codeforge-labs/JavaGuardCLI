package com.devmanchego.javaguard.change;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Specification section 9.2. Lines are 1-based, in the current file. */
class ChangedLinesTest {

    private static final String FIVE = "l1\nl2\nl3\nl4\nl5\n";

    @Test
    void identicalContentHasNoChangedLines() {
        assertThat(ChangedLines.between(FIVE, FIVE).isEmpty()).isTrue();
    }

    @Test
    void insertedLineIsChanged() {
        LineRanges r = ChangedLines.between(FIVE, "l1\nl2\nNEW\nl3\nl4\nl5\n");

        assertThat(r.contains(3)).isTrue();
        assertThat(r.contains(2)).isFalse();
        assertThat(r.contains(4)).isFalse();
    }

    @Test
    void modifiedLineIsChanged() {
        LineRanges r = ChangedLines.between(FIVE, "l1\nCHANGED\nl3\nl4\nl5\n");

        assertThat(r.contains(2)).isTrue();
        assertThat(r.contains(1)).isFalse();
        assertThat(r.contains(3)).isFalse();
    }

    @Test
    void deletionMarksTheLinesAroundTheGap() {
        // l3 removed: in the current file the gap sits between line 2 (l2) and line 3 (l4).
        LineRanges r = ChangedLines.between(FIVE, "l1\nl2\nl4\nl5\n");

        assertThat(r.contains(2)).isTrue();
        assertThat(r.contains(3)).isTrue();
        assertThat(r.contains(1)).isFalse();
        assertThat(r.contains(4)).isFalse();
    }

    @Test
    void deletionAtEndOfFileMarksTheNewLastLine() {
        LineRanges r = ChangedLines.between(FIVE, "l1\nl2\nl3\nl4\n");

        assertThat(r.contains(4)).isTrue();
        assertThat(r.contains(5)).isFalse(); // no such line in the current file
    }

    @Test
    void deletionAtStartOfFileMarksTheNewFirstLine() {
        LineRanges r = ChangedLines.between(FIVE, "l2\nl3\nl4\nl5\n");

        assertThat(r.contains(1)).isTrue();
        assertThat(r.contains(2)).isFalse();
    }

    @Test
    void emptyBaselineMakesEveryLineChanged() {
        LineRanges r = ChangedLines.between("", FIVE);

        for (int line = 1; line <= 5; line++) {
            assertThat(r.contains(line)).isTrue();
        }
        assertThat(r.contains(6)).isFalse();
    }

    @Test
    void lineEndingsAreIgnored() {
        assertThat(ChangedLines.between(FIVE, FIVE.replace("\n", "\r\n")).isEmpty()).isTrue();
    }

    @Test
    void emptyCurrentHasNoLinesToMark() {
        assertThat(ChangedLines.between(FIVE, "").isEmpty()).isTrue();
    }

    @Test
    void rangesMergeAndReportThemselves() {
        LineRanges r = LineRanges.builder().add(5, 7).add(1, 1).add(6, 9).add(2, 2).build();

        assertThat(r.toString()).isEqualTo("[1-2, 5-9]");
        assertThat(r.contains(0)).isFalse();
        assertThat(r.contains(3)).isFalse();
    }
}
