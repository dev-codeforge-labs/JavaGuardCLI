package com.devmanchego.javaguard.cli.command;

import com.devmanchego.javaguard.cli.Main;

import java.nio.file.Path;
import java.util.Map;

/** Project root for manual commands: {@code --project-dir}, agent environment, else the current directory. */
final class ProjectRoots {

    private ProjectRoots() {
    }

    static Path resolve(Main main) {
        if (main != null && main.projectDir != null) {
            return main.projectDir.toAbsolutePath().normalize();
        }
        Map<String, String> env = ProcessEnvironment.get();
        for (String name : new String[] {"CLAUDE_PROJECT_DIR", "GEMINI_PROJECT_DIR"}) {
            String value = env.get(name);
            if (value != null && !value.isBlank()) {
                return Path.of(value).toAbsolutePath().normalize();
            }
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }
}
