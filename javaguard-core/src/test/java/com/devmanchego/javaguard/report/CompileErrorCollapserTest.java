package com.devmanchego.javaguard.report;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Fingerprint;
import com.devmanchego.javaguard.model.Severity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Specification section 12.2. */
class CompileErrorCollapserTest {

    private static Finding javac(Path file, int line, String symbol) {
        Map<String, String> detail = symbol == null ? Map.of() : Map.of("symbol", symbol);
        return new Finding("JAVAC", "Compilation error", Category.COMPILATION, Severity.CRITICAL, "javac",
                Optional.of(file), line, 1, line, "cannot find symbol", "check the import", Optional.empty(), detail,
                new Fingerprint(Integer.toHexString((file + ":" + line + ":" + symbol).hashCode() & 0xFFFFFFF)));
    }

    private static Finding other(String ruleId, Path file, int line) {
        return new Finding(ruleId, "t", Category.RELIABILITY, Severity.MAJOR, "ast", Optional.of(file), line, 1, line,
                "msg", "fix", Optional.empty(), Map.of(), new Fingerprint(Integer.toHexString((ruleId + line).hashCode() & 0xFFFFFFF)));
    }

    @Test
    void sameFileSameSymbolCascadeCollapsesToOne() {
        Path file = Path.of("A.java");
        List<Finding> in = List.of(javac(file, 10, "List"), javac(file, 42, "List"), javac(file, 50, "List"));

        List<Finding> out = CompileErrorCollapser.collapse(in);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).line()).isEqualTo(10);
        assertThat(out.get(0).detail()).containsEntry(CompileErrorCollapser.CASCADE_COUNT, "2");
    }

    private static Finding javacMessage(Path file, int line, String message) {
        return new Finding("JAVAC", "Compilation error", Category.COMPILATION, Severity.CRITICAL, "maven",
                Optional.of(file), line, 1, line, message, "fix", Optional.empty(), Map.of(),
                new Fingerprint(Integer.toHexString((file + ":" + line + message).hashCode() & 0xFFFFFFF)));
    }

    @Test
    void aByteOrderMarkErrorSwallowsTheEchoesOnItsLineButNotOtherFilesOrLines() {
        Path foo = Path.of("Foo.java");
        Path bar = Path.of("Bar.java");
        List<Finding> in = List.of(
                javacMessage(foo, 1, "illegal character: '\ufeff'"),
                javacMessage(foo, 1, "class, interface, enum, or record expected"),
                javacMessage(foo, 9, "cannot find symbol"),
                javacMessage(bar, 1, "class, interface, enum, or record expected"));

        List<Finding> out = CompileErrorCollapser.collapse(in);

        assertThat(out).extracting(Finding::message).containsExactly("illegal character: '\ufeff'", "cannot find symbol",
                "class, interface, enum, or record expected");
        assertThat(out.get(2).file()).contains(bar);
    }

    @Test
    void differentSymbolsAreNotCollapsedTogether() {
        Path file = Path.of("A.java");
        List<Finding> in = List.of(javac(file, 10, "List"), javac(file, 20, "Map"));

        assertThat(CompileErrorCollapser.collapse(in)).hasSize(2);
    }

    @Test
    void differentFilesSameSymbolAreNotCollapsedTogether() {
        List<Finding> in = List.of(javac(Path.of("A.java"), 10, "List"), javac(Path.of("B.java"), 10, "List"));

        assertThat(CompileErrorCollapser.collapse(in)).hasSize(2);
    }

    @Test
    void findingsWithoutASymbolDetailAreNeverCascaded() {
        Path file = Path.of("A.java");
        List<Finding> in = List.of(javac(file, 10, null), javac(file, 20, null));

        assertThat(CompileErrorCollapser.collapse(in)).hasSize(2);
    }

    @Test
    void nonJavacFindingsAreNeverTouched() {
        Path file = Path.of("A.java");
        List<Finding> in = List.of(other("JG-REL-003", file, 10), other("JG-REL-003", file, 10));

        assertThat(CompileErrorCollapser.collapse(in)).hasSize(2);
    }

    @Test
    void exactlyTenJavacFindingsInOneFileAreNotCapped() {
        Path file = Path.of("A.java");
        List<Finding> in = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            in.add(javac(file, i, "Symbol" + i));
        }

        assertThat(CompileErrorCollapser.collapse(in)).hasSize(10);
    }

    @Test
    void elevenDistinctJavacFindingsAreCappedToFivePlusOneSummary() {
        Path file = Path.of("A.java");
        List<Finding> in = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            in.add(javac(file, i, "Symbol" + i));
        }

        List<Finding> out = CompileErrorCollapser.collapse(in);

        assertThat(out).hasSize(6);
        assertThat(out.subList(0, 5)).extracting(Finding::line).containsExactly(0, 1, 2, 3, 4);
        Finding summary = out.get(5);
        assertThat(summary.detail()).containsEntry(CompileErrorCollapser.SUMMARY, "true");
        assertThat(summary.message()).contains("6 more compile error(s)").contains("showing the first 5");
        assertThat(summary.file()).contains(file);
        assertThat(summary.remediation()).isEmpty();
    }

    @Test
    void perFileCapAppliesIndependentlyToEachFile() {
        Path a = Path.of("A.java");
        Path b = Path.of("B.java");
        List<Finding> in = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            in.add(javac(a, i, "SymA" + i));
        }
        in.add(javac(b, 0, "SymB"));

        List<Finding> out = CompileErrorCollapser.collapse(in);

        assertThat(out).hasSize(7); // 5 + 1 summary for A, 1 untouched for B
        assertThat(out.get(6).file()).contains(b);
        assertThat(out.get(6).detail()).doesNotContainKey(CompileErrorCollapser.SUMMARY);
    }

    @Test
    void cascadeCollapseRunsBeforeThePerFileCapSoItCanBringTheCountBackUnderTheThreshold() {
        Path file = Path.of("A.java");
        List<Finding> in = new ArrayList<>();
        // 15 raw findings, but they only name 3 distinct missing symbols: collapses to 3, well under 10.
        for (int i = 0; i < 15; i++) {
            in.add(javac(file, i, "Symbol" + (i % 3)));
        }

        List<Finding> out = CompileErrorCollapser.collapse(in);

        assertThat(out).hasSize(3);
        assertThat(out).noneMatch(f -> f.detail().containsKey(CompileErrorCollapser.SUMMARY));
    }

    @Test
    void emptyListStaysEmpty() {
        assertThat(CompileErrorCollapser.collapse(List.of())).isEmpty();
    }
}
