package com.devmanchego.javaguard.project;

import java.util.List;
import java.util.Objects;

/**
 * A resolved build-tool command (specification &sect;7 rule 3).
 *
 * @param command absolute-path command, ready to prepend goals/arguments to
 * @param source  {@code "wrapper"}, {@code "daemon"}, or {@code "path"} (for
 *                {@code doctor} and logs, not for behavior)
 */
public record BuildToolExecutable(List<String> command, String source) {

    public BuildToolExecutable {
        command = List.copyOf(command);
        Objects.requireNonNull(source, "source");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
    }
}
