package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.process.ProcessResult;
import com.devmanchego.javaguard.process.ProcessRunner;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link WorkspaceChanges} from {@code git status --porcelain=v1 -z}. Read-only: JavaGuard never runs a
 * state-changing git command (specification &sect;1.1). Paths in porcelain output are relative to the
 * repository's top level, not to the project root, so the top level is resolved first.
 */
public final class GitWorkspace implements WorkspaceChanges {

    private static final Logger LOG = Logger.getLogger(GitWorkspace.class.getName());

    private final ProcessRunner runner;
    private final Path projectRoot;
    /** The project root with symbolic links resolved: git reports paths this way ({@code /tmp} is {@code /private/tmp} on macOS). */
    private final Path realRoot;
    private final Duration timeout;
    private volatile String problem;

    public GitWorkspace(ProcessRunner runner, Path projectRoot, Duration timeout) {
        this(runner, projectRoot, realPathOf(projectRoot), timeout);
    }

    GitWorkspace(ProcessRunner runner, Path projectRoot, Path realRoot, Duration timeout) {
        this.runner = runner;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.realRoot = realRoot.toAbsolutePath().normalize();
        this.timeout = timeout;
    }

    private static Path realPathOf(Path root) {
        try {
            return root.toRealPath();
        } catch (IOException | RuntimeException e) {
            return root.toAbsolutePath().normalize();
        }
    }

    @Override
    public Optional<String> problem() {
        return Optional.ofNullable(problem);
    }

    /** What the user can do about a repository git will not read, when that is why it said nothing. */
    static Optional<String> explain(String gitOutput, Path root) {
        if (gitOutput != null && gitOutput.contains("dubious ownership")) {
            return Optional.of("WARNING: git refuses to read this repository because it belongs to another user ('dubious ownership'), "
                    + "so edits made through a shell cannot be detected. If you trust it, run: git config --global --add safe.directory "
                    + root.toString().replace('\\', '/'));
        }
        return Optional.empty();
    }

    @Override
    public Optional<Set<Path>> dirtyFiles() {
        try {
            ProcessResult top = runner.run(List.of("git", "-C", projectRoot.toString(), "rev-parse", "--show-toplevel"),
                    projectRoot, Map.of(), timeout);
            if (!top.succeeded()) {
                problem = explain(top.output(), projectRoot).orElse(null);
                problem().ifPresent(text -> LOG.warning(text.replaceFirst("^WARNING: ", "")));
                return Optional.empty();
            }
            problem = null;
            Path topLevel = Path.of(top.output().strip()).toAbsolutePath().normalize();
            ProcessResult status = runner.run(List.of("git", "-C", projectRoot.toString(), "-c", "core.quotepath=off",
                    "status", "--porcelain=v1", "-z", "--untracked-files=all"), projectRoot, Map.of(), timeout);
            if (!status.succeeded() || status.outputTruncated()) {
                return Optional.empty();
            }
            return Optional.of(parse(status.output(), topLevel));
        } catch (IOException | InvalidPathException e) {
            LOG.log(Level.FINE, "git is not available; the working tree cannot be inspected.", e);
            return Optional.empty();
        }
    }

    /** One NUL-terminated {@code XY path} entry each; a rename or copy is followed by its origin path. */
    Set<Path> parse(String porcelainZ, Path topLevel) {
        Set<Path> files = new LinkedHashSet<>();
        List<String> tokens = new ArrayList<>(List.of(porcelainZ.split("\0")));
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.length() < 4) {
                continue;
            }
            char x = token.charAt(0);
            char y = token.charAt(1);
            addIfInside(files, topLevel.resolve(token.substring(3)));
            if (x == 'R' || x == 'C' || y == 'R' || y == 'C') {
                i++;
                if (i < tokens.size()) {
                    addIfInside(files, topLevel.resolve(tokens.get(i)));
                }
            }
        }
        return files;
    }

    private void addIfInside(Set<Path> files, Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (normalized.startsWith(projectRoot) && !normalized.equals(projectRoot)) {
            files.add(normalized);
        } else if (normalized.startsWith(realRoot) && !normalized.equals(realRoot)) {
            // The project was reached through a symbolic link: keep the paths in the namespace the agent and the rest of
            // JavaGuard use, or every file would look outside the project.
            files.add(projectRoot.resolve(realRoot.relativize(normalized)));
        }
    }
}
