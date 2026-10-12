package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.process.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 9: the working tree as the end-of-turn gate sees it (specification &sect;10.3 step 1). */
class GitWorkspaceTest {

    @TempDir
    Path repo;

    private GitWorkspace workspace(Path root) {
        return new GitWorkspace(new ProcessRunner(), root, Duration.ofSeconds(20));
    }

    private void git(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString(), "-c", "user.name=t", "-c", "user.email=t@t"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).as("git " + String.join(" ", args)).isZero();
    }

    private Path write(String relative, String content) throws IOException {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void reportsModifiedDeletedRenamedAndUntrackedFilesButNotCleanOnes() throws Exception {
        write("src/Clean.java", "class Clean {}");
        write("src/Modified.java", "class Modified {}");
        write("src/Deleted.java", "class Deleted {}");
        write("src/Old.java", "class Old { int a; int b; int c; }");
        git("init", "-q");
        git("add", ".");
        git("commit", "-q", "-m", "start");

        write("src/Modified.java", "class Modified { int x; }");
        Files.delete(repo.resolve("src/Deleted.java"));
        git("mv", "src/Old.java", "src/New.java");
        write("src/Untracked.java", "class Untracked {}");

        Set<Path> dirty = workspace(repo).dirtyFiles().orElseThrow();

        assertThat(dirty).contains(repo.resolve("src/Modified.java"), repo.resolve("src/Deleted.java"),
                repo.resolve("src/New.java"), repo.resolve("src/Old.java"), repo.resolve("src/Untracked.java"));
        assertThat(dirty).doesNotContain(repo.resolve("src/Clean.java"));
    }

    @Test
    void aProjectInASubdirectoryOfTheRepositoryGetsPathsResolvedAgainstTheTopLevel() throws Exception {
        write("app/src/A.java", "class A {}");
        write("other/B.java", "class B {}");
        git("init", "-q");
        git("add", ".");
        git("commit", "-q", "-m", "start");
        write("app/src/A.java", "class A { int x; }");
        write("other/B.java", "class B { int y; }");

        Set<Path> dirty = workspace(repo.resolve("app")).dirtyFiles().orElseThrow();

        assertThat(dirty).containsExactly(repo.resolve("app/src/A.java"));
    }

    @Test
    void aDirectoryThatIsNotARepositoryHasNoAnswer() {
        assertThat(workspace(repo).dirtyFiles()).isEmpty();
    }

    @Test
    void untrackedFilesInsideIgnoredDirectoriesAreNotReported() throws Exception {
        write(".gitignore", "target/\n.javaguard/\n");
        write("src/A.java", "class A {}");
        git("init", "-q");
        git("add", ".");
        git("commit", "-q", "-m", "start");
        write("target/classes/A.class", "x");
        write(".javaguard/state/x.json", "{}");

        assertThat(workspace(repo).dirtyFiles().orElseThrow()).isEmpty();
    }

    @Test
    void parseHandlesRenameOriginsAndShortEntries() {
        String porcelain = " M a.java\0R  new.java\0old.java\0?? c.java\0\0xx\0";

        Set<Path> parsed = workspace(repo).parse(porcelain, repo);

        assertThat(parsed).containsExactlyInAnyOrder(repo.resolve("a.java"), repo.resolve("new.java"),
                repo.resolve("old.java"), repo.resolve("c.java"));
    }

    @Test
    void aProjectReachedThroughASymbolicLinkKeepsItsOwnPaths() {
        // git reports /real/proj/...; the agent, and every other path JavaGuard holds, says /link/proj/...
        Path link = Path.of("/link/proj").toAbsolutePath().normalize();
        Path real = Path.of("/real/proj").toAbsolutePath().normalize();
        GitWorkspace viaLink = new GitWorkspace(new ProcessRunner(), link, real, Duration.ofSeconds(5));

        Set<Path> parsed = viaLink.parse("?? src/A.java\0 M pom.xml\0", real);

        assertThat(parsed).containsExactlyInAnyOrder(link.resolve("src/A.java"), link.resolve("pom.xml"));
    }

    @Test
    void aRepositoryOfAnotherUserIsExplainedNotMistakenForAClean() {
        String refusal = "fatal: detected dubious ownership in repository at '/work/proj'\nTo add an exception ...";

        assertThat(GitWorkspace.explain(refusal, Path.of("/work/proj")))
                .hasValueSatisfying(text -> assertThat(text).startsWith("WARNING:")
                        .contains("another user").contains("git config --global --add safe.directory /work/proj"));
        assertThat(GitWorkspace.explain("fatal: not a git repository", Path.of("/work/proj"))).isEmpty();
        assertThat(workspace(repo).problem()).isEmpty();
    }
}
