package com.devmanchego.javaguard.model;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One problem reported by any check (specification &sect;6.2).
 *
 * @param ruleId      stable id: {@code JG-SEC-001}, {@code JAVAC}, {@code TEST},
 *                    {@code PMD:CloseResource}, {@code EP:BadImport}, {@code SB:...}
 * @param title       short rule name
 * @param category    finding category
 * @param severity    finding severity
 * @param engine      producing engine: {@code ast}, {@code javac}, {@code errorprone},
 *                    {@code pmd}, {@code spotbugs}, {@code maven}, {@code gradle}, ...
 * @param file        project-relative path, when the finding is tied to a file
 * @param line        1-based line, 0 when unknown; never part of the fingerprint
 * @param column      1-based column, 0 when unknown
 * @param endLine     1-based end line, 0 when unknown
 * @param message     what is wrong, specific to this occurrence
 * @param remediation what to do, one or two sentences
 * @param snippet     offending source line(s), trimmed, at most 3 lines
 * @param detail      extra structured data (test name, expected/actual, symbol/location)
 * @param fingerprint line-independent identity
 */
public record Finding(
        String ruleId,
        String title,
        Category category,
        Severity severity,
        String engine,
        Optional<Path> file,
        int line,
        int column,
        int endLine,
        String message,
        String remediation,
        Optional<String> snippet,
        Map<String, String> detail,
        Fingerprint fingerprint
) {

    public Finding {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(remediation, "remediation");
        Objects.requireNonNull(snippet, "snippet");
        detail = Map.copyOf(Objects.requireNonNull(detail, "detail"));
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (line < 0 || column < 0 || endLine < 0) {
            throw new IllegalArgumentException("line/column/endLine must be >= 0");
        }
        file.ifPresent(p -> {
            if (p.isAbsolute()) {
                throw new IllegalArgumentException("Finding file must be project-relative: " + p);
            }
        });
    }
}
