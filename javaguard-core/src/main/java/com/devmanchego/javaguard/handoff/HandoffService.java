package com.devmanchego.javaguard.handoff;

import com.devmanchego.javaguard.config.JavaGuardConfig;
import com.devmanchego.javaguard.state.JavaGuardPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds, redacts and writes a handoff (specification &sect;14.1): {@code .javaguard/handoff/HANDOFF-<session>-<timestamp>.md}
 * and, when {@code handoff.copyToProjectRoot} is on, {@code AI_HANDOFF_CONTEXT.md} in the project root. Never throws:
 * a handoff that cannot be written must not turn a HALT into a crash.
 */
public final class HandoffService {

    public static final String ROOT_COPY_NAME = "AI_HANDOFF_CONTEXT.md";

    private static final Logger LOG = Logger.getLogger(HandoffService.class.getName());
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final JavaGuardPaths paths;
    private final JavaGuardConfig.Handoff settings;
    private final Clock clock;

    public HandoffService(JavaGuardPaths paths, JavaGuardConfig config, Clock clock) {
        this.paths = paths;
        this.settings = config.handoff();
        this.clock = clock;
    }

    /** Writes the handoff and returns its project-relative path (forward slashes), or empty when it could not be written. */
    public Optional<String> write(HandoffBuilder.Request request) {
        try {
            String document = new Redactor(paths.projectRoot(), settings.redactPatterns())
                    .redact(new HandoffBuilder(settings).build(request));
            Files.createDirectories(paths.handoffDir());
            Path target = paths.handoffDir().resolve("HANDOFF-" + SolutionPickup.shortId(request.sessionId()) + "-"
                    + STAMP.format(clock.instant()) + ".md");
            Files.writeString(target, document, StandardCharsets.UTF_8);
            if (settings.copyToProjectRoot()) {
                Files.writeString(paths.projectRoot().resolve(ROOT_COPY_NAME), document, StandardCharsets.UTF_8);
            }
            return Optional.of(paths.projectRoot().relativize(target).toString().replace('\\', '/'));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not write the handoff.", e);
            return Optional.empty();
        }
    }
}
