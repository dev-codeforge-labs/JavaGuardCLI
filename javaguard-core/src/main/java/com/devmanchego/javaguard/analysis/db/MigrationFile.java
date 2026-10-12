package com.devmanchego.javaguard.analysis.db;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What kind of database migration a path is, from its name and location only (the content of a Liquibase changelog is
 * checked by {@link LiquibaseChangelog}). Flyway migrations are recognised under a {@code db/migration} directory (Spring
 * Boot's default location and its vendor sub-directories) with Flyway's default prefixes; Liquibase changelogs under a
 * {@code db/changelog} directory or with "changelog" in their name.
 *
 * @param classpathDirectory the directory as seen on the class path ({@code db/migration}), which is what Flyway scans
 */
public record MigrationFile(Type type, String version, String description, String fileName, String classpathDirectory) {

    public enum Type { FLYWAY_VERSIONED, FLYWAY_JAVA, FLYWAY_REPEATABLE, FLYWAY_UNDO, LIQUIBASE }

    private static final Pattern FLYWAY = Pattern.compile("^([VUR])((?:\\d+(?:[._]\\d+)*)?)__(.+)\\.(sql|java|kt)$");
    private static final Pattern LIQUIBASE_NAME = Pattern.compile("(?i).+\\.(xml|ya?ml|json|sql)$");
    private static final List<String> SOURCE_ROOTS = List.of("src/main/resources/", "src/main/java/", "src/main/kotlin/");

    public static Optional<MigrationFile> classify(String relativePath) {
        String path = "/" + relativePath.replace('\\', '/');
        int slash = path.lastIndexOf('/');
        String name = path.substring(slash + 1);
        String directory = path.substring(0, slash + 1);
        if (directory.contains("/db/migration/")) {
            Matcher m = FLYWAY.matcher(name);
            if (m.matches()) {
                String prefix = m.group(1);
                String extension = m.group(4);
                Type type = switch (prefix) {
                    case "V" -> extension.equals("sql") ? Type.FLYWAY_VERSIONED : Type.FLYWAY_JAVA;
                    case "U" -> Type.FLYWAY_UNDO;
                    default -> Type.FLYWAY_REPEATABLE;
                };
                boolean versioned = !prefix.equals("R");
                if (versioned == m.group(2).isEmpty() || type == Type.FLYWAY_REPEATABLE && !extension.equals("sql")) {
                    return Optional.empty();
                }
                return Optional.of(new MigrationFile(type, m.group(2), m.group(3), name, classpathDirectory(directory)));
            }
            return Optional.empty();
        }
        boolean changelogPlace = directory.contains("/db/changelog/") || name.toLowerCase(Locale.ROOT).contains("changelog");
        if (changelogPlace && LIQUIBASE_NAME.matcher(name).matches()) {
            return Optional.of(new MigrationFile(Type.LIQUIBASE, "", "", name, classpathDirectory(directory)));
        }
        return Optional.empty();
    }

    /** {@code /mod/src/main/resources/db/migration/} becomes {@code db/migration}. */
    private static String classpathDirectory(String directory) {
        String d = directory.substring(1);
        for (String root : SOURCE_ROOTS) {
            int at = d.indexOf(root);
            if (at >= 0) {
                d = d.substring(at + root.length());
                break;
            }
        }
        return d.endsWith("/") ? d.substring(0, d.length() - 1) : d;
    }

    /** Flyway versions compare numerically by part ({@code 1.1} = {@code 1_1} = {@code 1.01}), trailing zeros ignored. */
    public String normalizedVersion() {
        return normalize(version);
    }

    static String normalize(String version) {
        List<BigInteger> parts = new ArrayList<>();
        for (String part : version.replace('_', '.').split("\\.")) {
            parts.add(part.isEmpty() ? BigInteger.ZERO : new BigInteger(part));
        }
        while (parts.size() > 1 && parts.get(parts.size() - 1).signum() == 0) {
            parts.remove(parts.size() - 1);
        }
        return String.join(".", parts.stream().map(BigInteger::toString).toList());
    }

    public boolean isFlyway() {
        return type != Type.LIQUIBASE;
    }
}
