package com.devmanchego.javaguard.change;

import com.devmanchego.javaguard.process.ProcessResult;
import com.devmanchego.javaguard.process.ProcessRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link GitClient} over the {@code git} executable, through
 * {@link ProcessRunner} (UTF-8, timeout, tree kill, recursion guard).
 *
 * <p>Runs {@code git -C <dir> show HEAD:./<relative path>} from the nearest
 * existing ancestor directory of the file, so it also works for files (or
 * whole directories) deleted in the session. {@code HEAD:./path} is resolved
 * relative to {@code -C}, so the repository root never needs to be computed.
 */
public final class ProcessGitClient implements GitClient {

    private static final Logger LOG = Logger.getLogger(ProcessGitClient.class.getName());

    private final ProcessRunner runner;
    private final Duration timeout;

    public ProcessGitClient(ProcessRunner runner, Duration timeout) {
        this.runner = runner;
        this.timeout = timeout;
    }

    @Override
    public Optional<byte[]> showHead(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path dir = absolute.getParent();
        while (dir != null && !Files.isDirectory(dir)) {
            dir = dir.getParent();
        }
        if (dir == null) {
            return Optional.empty();
        }
        String relative = dir.relativize(absolute).toString().replace('\\', '/');
        List<String> command = List.of("git", "-C", dir.toString(), "-c", "core.quotepath=off",
                "show", "HEAD:./" + relative);
        try {
            ProcessResult result = runner.run(command, dir, Map.of(), timeout);
            if (!result.succeeded() || result.outputTruncated()) {
                return Optional.empty();
            }
            return Optional.of(result.output().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.log(Level.FINE, "git is not available; no git baseline for " + absolute, e);
            return Optional.empty();
        }
    }
}
