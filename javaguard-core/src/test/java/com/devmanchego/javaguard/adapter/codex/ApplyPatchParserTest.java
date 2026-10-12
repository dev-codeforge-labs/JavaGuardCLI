package com.devmanchego.javaguard.adapter.codex;

import com.devmanchego.javaguard.model.ChangeType;
import com.devmanchego.javaguard.model.EditFragment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Table tests for the Codex {@code apply_patch} envelope parser (contracts section 5.2). */
class ApplyPatchParserTest {

    @Test
    void updateWithOneHunk() {
        List<PatchFileOp> ops = ApplyPatchParser.parse("""
                *** Begin Patch
                *** Update File: src/A.java
                @@
                 keep
                -old
                +new
                *** End Patch
                """);

        assertThat(ops).containsExactly(new PatchFileOp(ChangeType.UPDATE, "src/A.java", Optional.empty(),
                List.of(new EditFragment("keep\nold", "keep\nnew", false))));
    }

    @Test
    void contextHeaderAfterAtAtIsNotContent() {
        List<PatchFileOp> ops = ApplyPatchParser.parse("""
                *** Begin Patch
                *** Update File: src/A.java
                @@ class A
                -int x;
                +long x;
                *** End Patch
                """);

        assertThat(ops.get(0).fragments()).containsExactly(new EditFragment("int x;", "long x;", false));
    }

    @Test
    void multipleHunksBecomeOneFragmentEach() {
        List<PatchFileOp> ops = ApplyPatchParser.parse("""
                *** Begin Patch
                *** Update File: src/A.java
                @@
                -a
                +b
                @@
                -c
                +d
                *** End Patch
                """);

        assertThat(ops.get(0).fragments()).containsExactly(
                new EditFragment("a", "b", false),
                new EditFragment("c", "d", false));
    }

    @Test
    void updateWithMoveReportsDestination() {
        List<PatchFileOp> ops = ApplyPatchParser.parse("""
                *** Begin Patch
                *** Update File: src/old/A.java
                *** Move to: src/new/A.java
                @@
                -package old;
                +package new;
                *** End Patch
                """);

        assertThat(ops).containsExactly(new PatchFileOp(ChangeType.MOVE, "src/old/A.java", Optional.of("src/new/A.java"),
                List.of(new EditFragment("package old;", "package new;", false))));
    }

    @Test
    void addFileHasNoFragments() {
        List<PatchFileOp> ops = ApplyPatchParser.parse("""
                *** Begin Patch
                *** Add File: src/B.java
                +class B {}
                *** End Patch
                """);

        assertThat(ops).containsExactly(new PatchFileOp(ChangeType.ADD, "src/B.java", Optional.empty(), List.of()));
    }

    @Test
    void deleteFile() {
        List<PatchFileOp> ops = ApplyPatchParser.parse("""
                *** Begin Patch
                *** Delete File: src/C.java
                *** End Patch
                """);

        assertThat(ops).containsExactly(new PatchFileOp(ChangeType.DELETE, "src/C.java", Optional.empty(), List.of()));
    }

    @Test
    void severalFilesKeepPatchOrder() {
        List<PatchFileOp> ops = ApplyPatchParser.parse("""
                *** Begin Patch
                *** Add File: a.txt
                +x
                *** Delete File: b.txt
                *** Update File: c.txt
                @@
                -1
                +2
                *** End Patch
                """);

        assertThat(ops).extracting(PatchFileOp::path).containsExactly("a.txt", "b.txt", "c.txt");
        assertThat(ops).extracting(PatchFileOp::type).containsExactly(ChangeType.ADD, ChangeType.DELETE, ChangeType.UPDATE);
    }

    @Test
    void endOfFileMarkerIsIgnored() {
        List<PatchFileOp> ops = ApplyPatchParser.parse("""
                *** Begin Patch
                *** Update File: A.java
                @@
                -}
                +}
                +// trailer
                *** End of File
                *** End Patch
                """);

        assertThat(ops.get(0).fragments()).containsExactly(new EditFragment("}", "}\n// trailer", false));
    }

    @Test
    void envelopeMarkersAreOptional() {
        List<PatchFileOp> ops = ApplyPatchParser.parse("""
                *** Update File: A.java
                @@
                -x
                +y
                """);

        assertThat(ops).hasSize(1);
        assertThat(ops.get(0).fragments()).containsExactly(new EditFragment("x", "y", false));
    }

    @Test
    void crlfLineEndingsAreAccepted() {
        List<PatchFileOp> ops = ApplyPatchParser.parse(
                "*** Begin Patch\r\n*** Update File: A.java\r\n@@\r\n-x\r\n+y\r\n*** End Patch\r\n");

        assertThat(ops.get(0).path()).isEqualTo("A.java");
        assertThat(ops.get(0).fragments()).containsExactly(new EditFragment("x", "y", false));
    }

    @Test
    void hunkLinesWithoutAtAtStartAnImplicitHunk() {
        List<PatchFileOp> ops = ApplyPatchParser.parse("""
                *** Update File: A.java
                 ctx
                -x
                +y
                """);

        assertThat(ops.get(0).fragments()).containsExactly(new EditFragment("ctx\nx", "ctx\ny", false));
    }

    @Test
    void emptyLineInsideHunkIsAnEmptyContextLine() {
        List<PatchFileOp> ops = ApplyPatchParser.parse(
                "*** Update File: A.java\n@@\n-a\n\n+b\n");

        assertThat(ops.get(0).fragments()).containsExactly(new EditFragment("a\n", "\nb", false));
    }

    @Test
    void strayLinesOutsideAnyFileAreIgnored() {
        List<PatchFileOp> ops = ApplyPatchParser.parse("""
                some preamble the model wrote
                *** Begin Patch
                *** Delete File: X.java
                *** End Patch
                """);

        assertThat(ops).extracting(PatchFileOp::path).containsExactly("X.java");
    }

    @Test
    void emptyOrBlankPatchYieldsNoOperations() {
        assertThat(ApplyPatchParser.parse("")).isEmpty();
        assertThat(ApplyPatchParser.parse("   \n")).isEmpty();
        assertThat(ApplyPatchParser.parse(null)).isEmpty();
    }

    @Test
    void pathsAreTrimmed() {
        List<PatchFileOp> ops = ApplyPatchParser.parse("*** Delete File:   spaced/Path.java   \n");

        assertThat(ops.get(0).path()).isEqualTo("spaced/Path.java");
    }
}
