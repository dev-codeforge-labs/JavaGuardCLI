package com.devmanchego.javaguard.gate;

import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.config.ConfigSources;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.GateResult;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.model.Verdict;
import com.devmanchego.javaguard.state.JavaGuardPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Keeps an agent from switching JavaGuard off by editing what configures it. At the first hook of a session the files that
 * control JavaGuard are recorded: the project's {@code javaguard.yml}, the user's {@code ~/.javaguard/config.yml}, and
 * JavaGuard's own hook entries in each agent's settings. For the rest of the session
 * <ul>
 *   <li>the configuration is read from the copies taken then, so an edit has no effect, and</li>
 *   <li>a change to any of those files is a BLOCKER ({@value #RULE_ID}) until it is reverted.</li>
 * </ul>
 * A change the user means to keep takes a new session, or {@code javaguard baseline reset}.
 *
 * <p>Hook files are compared only on JavaGuard's own entries, so an agent that rewrites its other settings (permissions,
 * themes) does not trip it.
 */
public final class ConfigGuard {

    public static final String RULE_ID = "JG-SUP-003";
    static final String DIR_NAME = "config-guard";
    private static final String MANIFEST = "manifest.json";
    private static final String PROJECT_COPY = "project.yml";
    private static final String USER_COPY = "user.yml";
    private static final String ABSENT = "absent";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern OWN_HOOK = Pattern.compile("\\bhook\\s+--agent\\s+(claude|gemini|codex)\\s+--gate\\s+[a-z-]+\\b");

    /** What is guarded: label, then where it lives. Hook files are relative to the project root. */
    private static final Map<String, String> HOOK_FILES = Map.of(
            "Claude Code hooks (.claude/settings.json)", ".claude/settings.json",
            "Gemini CLI hooks (.gemini/settings.json)", ".gemini/settings.json",
            "Codex hooks (.codex/hooks.json)", ".codex/hooks.json");
    private static final String PROJECT_LABEL = "javaguard.yml";
    private static final String USER_LABEL = "~/.javaguard/config.yml";

    private final JavaGuardPaths paths;
    private final Path userHome;

    public ConfigGuard(JavaGuardPaths paths, Path userHome) {
        this.paths = paths;
        this.userHome = userHome;
    }

    private Path dir(String sessionId) {
        return paths.sessionDir(sessionId).resolve(DIR_NAME);
    }

    private Path projectFile() {
        return paths.projectRoot().resolve(ConfigSources.PROJECT_FILE_NAME);
    }

    private Path userFile() {
        return userHome.resolve(".javaguard").resolve("config.yml");
    }

    /** Records the guarded files the first time a session is seen; later calls do nothing. Never throws. */
    public void ensureBaseline(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            Path dir = dir(sessionId);
            if (Files.isRegularFile(dir.resolve(MANIFEST))) {
                return;
            }
            Files.createDirectories(dir);
            copyIfPresent(projectFile(), dir.resolve(PROJECT_COPY));
            copyIfPresent(userFile(), dir.resolve(USER_COPY));
            Path temporary = Files.createTempFile(dir, "manifest", ".tmp");
            Files.writeString(temporary, JSON.writeValueAsString(new TreeMap<>(fingerprints())), StandardCharsets.UTF_8);
            Files.move(temporary, dir.resolve(MANIFEST), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            // Protection is best effort: a failure here must not cost the agent anything.
        }
    }

    /** Project configuration as it was when the session began; empty when there was none (or no baseline yet). */
    public Optional<Path> frozenProjectFile(String sessionId) {
        return frozenCopy(sessionId, PROJECT_COPY);
    }

    /** User configuration as it was when the session began. */
    public Optional<Path> frozenUserFile(String sessionId) {
        return frozenCopy(sessionId, USER_COPY);
    }

    /** True once a session has a baseline: the configuration to use is then {@link #frozenProjectFile}/{@link #frozenUserFile}. */
    public boolean hasBaseline(String sessionId) {
        return sessionId != null && !sessionId.isBlank() && Files.isRegularFile(dir(sessionId).resolve(MANIFEST));
    }

    /** Labels of the guarded files that differ from the baseline; empty without a baseline. */
    public List<String> changed(String sessionId) {
        if (!hasBaseline(sessionId)) {
            return List.of();
        }
        try {
            JsonNode recorded = JSON.readTree(Files.readString(dir(sessionId).resolve(MANIFEST), StandardCharsets.UTF_8));
            List<String> changed = new ArrayList<>();
            fingerprints().forEach((label, now) -> {
                if (!now.equals(recorded.path(label).asText(ABSENT))) {
                    changed.add(label);
                }
            });
            return changed;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** The result with a blocking finding added when a guarded file changed; the result itself otherwise. */
    public GateResult enforce(GateResult result, String sessionId) {
        List<String> changed = changed(sessionId);
        if (changed.isEmpty()) {
            return result;
        }
        List<Finding> blocking = new ArrayList<>();
        for (String label : changed) {
            blocking.add(finding(label));
        }
        blocking.addAll(result.blocking());
        Verdict verdict = result.verdict() == Verdict.HALT ? Verdict.HALT : Verdict.BLOCK;
        return new GateResult(result.gate(), verdict, blocking, result.advisory(), result.notes(), result.escalation(),
                result.fullReport(), result.newSuppressions(), result.elapsed(), result.solution());
    }

    /** Forgets the baseline of a session (or of all of them), so the next hook records the current files. */
    public void reset(String sessionId) throws IOException {
        List<Path> dirs = new ArrayList<>();
        if (sessionId == null || sessionId.isBlank()) {
            if (Files.isDirectory(paths.sessionsDir())) {
                try (var sessions = Files.list(paths.sessionsDir())) {
                    sessions.forEach(s -> dirs.add(s.resolve(DIR_NAME)));
                }
            }
        } else {
            dirs.add(dir(sessionId));
        }
        for (Path dir : dirs) {
            if (Files.isDirectory(dir)) {
                try (var files = Files.list(dir)) {
                    for (Path file : (Iterable<Path>) files::iterator) {
                        Files.deleteIfExists(file);
                    }
                }
                Files.deleteIfExists(dir);
            }
        }
    }

    // --- internals ----------------------------------------------------------------------------

    private static Finding finding(String label) {
        String message = "JavaGuard's own configuration was changed during this session: " + label + ".";
        return new Finding(RULE_ID, "Agent changed JavaGuard's configuration", Category.SUPPRESSION, Severity.BLOCKER,
                "javaguard", Optional.empty(), 0, 0, 0, message,
                "Revert the change (restore " + label + " exactly as it was). Rewrite the code so the finding "
                        + "disappears instead of changing JavaGuard; if the configuration really must change, tell the user, "
                        + "who can edit it before the next session.",
                Optional.empty(), Map.of(), Fingerprints.of(RULE_ID, label, message));
    }

    private Map<String, String> fingerprints() {
        Map<String, String> now = new TreeMap<>();
        now.put(PROJECT_LABEL, fileHash(projectFile()));
        now.put(USER_LABEL, fileHash(userFile()));
        HOOK_FILES.forEach((label, relative) -> now.put(label, hooksFingerprint(paths.projectRoot().resolve(relative))));
        return now;
    }

    private Optional<Path> frozenCopy(String sessionId, String name) {
        if (!hasBaseline(sessionId)) {
            return Optional.empty();
        }
        Path copy = dir(sessionId).resolve(name);
        return Files.isRegularFile(copy) ? Optional.of(copy) : Optional.empty();
    }

    private static void copyIfPresent(Path source, Path target) throws IOException {
        if (Files.isRegularFile(source)) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String fileHash(Path file) {
        try {
            return Files.isRegularFile(file) ? sha256(Files.readAllBytes(file)) : ABSENT;
        } catch (IOException e) {
            return "unreadable";
        }
    }

    /** JavaGuard's hook entries of one agent file, whatever else the file holds. */
    private static String hooksFingerprint(Path file) {
        if (!Files.isRegularFile(file)) {
            return ABSENT;
        }
        try {
            JsonNode root = JSON.readTree(Files.readAllBytes(file));
            List<String> own = new ArrayList<>();
            JsonNode hooks = root.path("hooks");
            hooks.fields().forEachRemaining(event -> event.getValue().forEach(group -> group.path("hooks").forEach(hook -> {
                String command = hook.path("command").asText("");
                String windows = hook.path("commandWindows").asText("");
                if (OWN_HOOK.matcher(command).find() || OWN_HOOK.matcher(windows).find()) {
                    own.add(event.getKey() + "|" + group.path("matcher").asText("") + "|" + command + "|" + windows + "|"
                            + hook.path("timeout").asText(""));
                }
            })));
            own.sort(null);
            return own.isEmpty() ? "none" : sha256(String.join("\n", own).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            // Not valid JSON any more: compare it byte for byte, so breaking the file counts as a change.
            return fileHash(file);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
