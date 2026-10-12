package com.devmanchego.javaguard.state;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Layout of the {@code .javaguard/} state directory inside a project, and the
 * recursion-guard checks that let JavaGuardCLI disable itself.
 *
 * <p>Directory layout (specification &sect;19):
 *
 * <pre>
 * &lt;project root&gt;/.javaguard/
 * |-- .gitignore                self-ignore ("*"); the project's own .gitignore is never touched
 * |-- disabled                  (optional) presence disables all hooks
 * |-- cache/
 * |   |-- classpath/
 * |   |-- baseline-findings/
 * |   \-- tmp/
 * |-- state/
 * |-- reports/
 * |-- handoff/
 * \-- logs/
 * </pre>
 */
public final class JavaGuardPaths {

    /** Name of the state directory created inside a project root. */
    public static final String STATE_DIR_NAME = ".javaguard";

    /** Environment variable that disables JavaGuardCLI entirely (recursion guard). */
    public static final String DISABLE_ENV_VAR = "JAVAGUARD_DISABLED";

    private final Path projectRoot;
    private final Path stateDir;

    public JavaGuardPaths(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.stateDir = this.projectRoot.resolve(STATE_DIR_NAME);
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public Path stateDir() {
        return stateDir;
    }

    public Path cacheDir() {
        return stateDir.resolve("cache");
    }

    public Path classpathCacheDir() {
        return cacheDir().resolve("classpath");
    }

    public Path baselineFindingsCacheDir() {
        return cacheDir().resolve("baseline-findings");
    }

    public Path tmpDir() {
        return cacheDir().resolve("tmp");
    }

    public Path sessionsDir() {
        return stateDir.resolve("state");
    }

    public Path sessionDir(String sessionId) {
        return sessionsDir().resolve(sanitizeSessionId(sessionId));
    }

    public Path reportsDir() {
        return stateDir.resolve("reports");
    }

    public Path handoffDir() {
        return stateDir.resolve("handoff");
    }

    public Path logsDir() {
        return stateDir.resolve("logs");
    }

    public Path logFile() {
        return logsDir().resolve("javaguard.log");
    }

    public Path disabledMarkerFile() {
        return stateDir.resolve("disabled");
    }

    public Path gitignoreFile() {
        return stateDir.resolve(".gitignore");
    }

    /**
     * Creates every directory in the layout (idempotent) and writes the
     * self-ignore {@code .gitignore} if it does not already contain {@code *}.
     */
    public void ensureLayout() {
        try {
            for (Path dir : new Path[] {
                    stateDir, cacheDir(), classpathCacheDir(), baselineFindingsCacheDir(),
                    tmpDir(), sessionsDir(), reportsDir(), handoffDir(), logsDir()
            }) {
                Files.createDirectories(dir);
            }
            ensureSelfIgnore();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create JavaGuard state layout under " + stateDir, e);
        }
    }

    private void ensureSelfIgnore() throws IOException {
        Path gitignore = gitignoreFile();
        if (Files.exists(gitignore)) {
            String content = Files.readString(gitignore, StandardCharsets.UTF_8);
            if (content.lines().anyMatch(line -> line.strip().equals("*"))) {
                return;
            }
        }
        Files.writeString(gitignore, "*\n", StandardCharsets.UTF_8);
    }

    /**
     * True when JavaGuardCLI must do nothing and exit successfully: either the
     * environment variable {@value #DISABLE_ENV_VAR} is set to {@code "1"}
     * (used by JavaGuardCLI itself on every child process it spawns, so a
     * build or a consultation it starts never re-enters a hook), or the
     * marker file {@code .javaguard/disabled} exists in the project root.
     */
    public boolean isDisabled() {
        return isDisabled(System.getenv());
    }

    /** {@link #isDisabled()} against an explicit environment (testable). */
    public boolean isDisabled(java.util.Map<String, String> environment) {
        if ("1".equals(environment.get(DISABLE_ENV_VAR))) {
            return true;
        }
        return Files.exists(disabledMarkerFile());
    }

    private static String sanitizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "manual";
        }
        // Session ids are agent-controlled input; never let them escape the
        // sessions directory. '.' is excluded from the allowed set too, so a
        // session id of ".." (or containing it) can never resolve to a
        // traversal segment.
        String sanitized = sessionId.replaceAll("[^A-Za-z0-9_-]", "_");
        return sanitized.isBlank() ? "manual" : sanitized;
    }
}
