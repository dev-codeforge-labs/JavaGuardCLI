package com.devmanchego.javaguard.config;

import java.util.List;

/**
 * Configuration cannot be used: malformed YAML, wrong value types, or
 * validation failures. Manual commands exit 3; hook mode falls back to
 * defaults (specification &sect;18.1).
 */
public class ConfigException extends Exception {

    private final List<String> problems;

    public ConfigException(List<String> problems) {
        super("Invalid JavaGuard configuration:\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    public ConfigException(String problem, Throwable cause) {
        super("Invalid JavaGuard configuration:\n  - " + problem, cause);
        this.problems = List.of(problem);
    }

    /** Each problem as one line: {@code <source>: <key path>: <what is wrong>}. */
    public List<String> problems() {
        return problems;
    }
}
