package com.devmanchego.javaguard.analysis.db;

import com.devmanchego.javaguard.analysis.RuleInfo;
import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * JG-DB-001..003 (catalog &sect;1.12): changes to database migrations that break deployments. A migration counts as applied
 * when it existed before the session's first edit and, in a git repository, is committed: a migration that only exists in
 * the working tree has not reached any shared database, so editing it is free. The caller decides "applied"; these checks
 * compare the file's baseline with its current state.
 */
public final class MigrationChecks {

    public static final RuleInfo DB_001 = new RuleInfo("JG-DB-001", "Applied migration modified", Category.DATABASE, Severity.BLOCKER,
            "diff", "F");
    public static final RuleInfo DB_002 = new RuleInfo("JG-DB-002", "Migration renamed or deleted", Category.DATABASE, Severity.BLOCKER,
            "diff", "F");
    public static final RuleInfo DB_003 = new RuleInfo("JG-DB-003", "Duplicate migration version", Category.DATABASE, Severity.CRITICAL,
            "file", "F");
    public static final List<RuleInfo> RULES = List.of(DB_001, DB_002, DB_003);

    private static final String NEW_MIGRATION = "Restore the file exactly as committed and put the change in a new migration with the "
            + "next version. If this migration was never applied to any shared database, disable JG-DB-001 for this path in javaguard.yml.";

    private MigrationChecks() {
    }

    /** Whether the path is a migration these checks look at. */
    public static boolean isMigration(String relativePath) {
        return MigrationFile.classify(relativePath).isPresent();
    }

    /**
     * @param baseline the file before the session's first edit ({@code ""} when it did not exist)
     * @param current  the file now; empty when it was deleted
     * @param applied  the baseline is a migration databases may have applied (see the class comment)
     */
    public static List<Finding> check(String relativePath, Path absolute, String baseline, Optional<String> current, boolean applied) {
        Optional<MigrationFile> classified = MigrationFile.classify(relativePath);
        if (classified.isEmpty()) {
            return List.of();
        }
        MigrationFile file = classified.get();
        List<Finding> out = new ArrayList<>();
        boolean existed = !baseline.isEmpty();
        if (current.isEmpty()) {
            if (existed && applied) {
                deleted(relativePath, absolute, baseline, file, out);
            }
            return out;
        }
        if (existed && applied) {
            modified(relativePath, baseline, current.get(), file, out);
        }
        if (!existed && (file.type() == MigrationFile.Type.FLYWAY_VERSIONED || file.type() == MigrationFile.Type.FLYWAY_JAVA)) {
            duplicate(relativePath, absolute, file).ifPresent(out::add);
        }
        return out;
    }

    private static void modified(String rel, String baseline, String current, MigrationFile file, List<Finding> out) {
        switch (file.type()) {
            case FLYWAY_VERSIONED -> {
                if (FlywayChecksum.of(baseline) != FlywayChecksum.of(current)) {
                    out.add(finding(DB_001, Severity.BLOCKER, rel, firstChangedLine(baseline, current), current, rel,
                            "Applied Flyway migration " + file.fileName() + " was modified: its checksum no longer matches the one "
                                    + "recorded in flyway_schema_history, so Flyway validation fails and the application does not start "
                                    + "on every database that applied it.", NEW_MIGRATION));
                }
            }
            case FLYWAY_JAVA -> {
                if (!squeeze(baseline).equals(squeeze(current))) {
                    out.add(finding(DB_001, Severity.MAJOR, rel, firstChangedLine(baseline, current), current, rel,
                            "Applied Java migration " + file.fileName() + " was modified: Flyway will not run it again, so databases "
                                    + "that applied the old version never get this change and drift from new ones.", NEW_MIGRATION));
                }
            }
            case LIQUIBASE -> liquibase(rel, baseline, current, file, out);
            default -> {
                // Repeatable migrations are re-applied when they change; undo migrations are not validated.
            }
        }
    }

    private static void liquibase(String rel, String baseline, String current, MigrationFile file, List<Finding> out) {
        Optional<Map<String, LiquibaseChangelog.ChangeSet>> before = LiquibaseChangelog.parse(file.fileName(), baseline);
        Optional<Map<String, LiquibaseChangelog.ChangeSet>> now = LiquibaseChangelog.parse(file.fileName(), current);
        if (before.isEmpty() || now.isEmpty()) {
            return;
        }
        for (LiquibaseChangelog.ChangeSet old : before.get().values()) {
            LiquibaseChangelog.ChangeSet changed = now.get().get(old.key());
            if (changed != null) {
                if (!changed.content().equals(old.content()) && !changed.runOnChange() && !changed.anyCheckSum()) {
                    out.add(finding(DB_001, Severity.BLOCKER, rel, changed.line(), current, rel + "#" + old.key(),
                            "Change set " + old.id() + " (author " + old.author() + ") of " + file.fileName() + " was modified after it "
                                    + "was applied: its checksum no longer matches DATABASECHANGELOG, so Liquibase fails at start-up "
                                    + "on every database that applied it.",
                            "Restore the change set and add a new one with the change. Only a change set marked runOnChange may be "
                                    + "edited in place."));
                }
                continue;
            }
            Optional<LiquibaseChangelog.ChangeSet> renamed = now.get().values().stream()
                    .filter(c -> !before.get().containsKey(c.key()) && c.content().equals(old.content())).findFirst();
            if (renamed.isPresent()) {
                out.add(finding(DB_002, Severity.BLOCKER, rel, renamed.get().line(), current, rel + "#" + old.key(),
                        "Change set " + old.id() + " (author " + old.author() + ") was re-identified as " + renamed.get().id()
                                + " (author " + renamed.get().author() + "): Liquibase sees a new change set and runs it again on "
                                + "databases that already applied it.",
                        "Keep the id and author of an applied change set unchanged."));
            } else {
                out.add(finding(DB_002, Severity.MAJOR, rel, 0, current, rel + "#" + old.key(),
                        "Applied change set " + old.id() + " (author " + old.author() + ") was removed from " + file.fileName()
                                + ": databases that applied it keep its effect while new databases never get it.",
                        "Restore the change set; to undo it, add a new change set that reverses it."));
            }
        }
    }

    private static void deleted(String rel, Path absolute, String baseline, MigrationFile file, List<Finding> out) {
        switch (file.type()) {
            case FLYWAY_VERSIONED, FLYWAY_JAVA -> {
                Optional<String> renamedTo = file.type() == MigrationFile.Type.FLYWAY_VERSIONED ? sameChecksum(absolute, baseline)
                        : Optional.empty();
                if (renamedTo.isPresent() || higherVersionExists(absolute, file)) {
                    String what = renamedTo.map(n -> " was renamed to " + n).orElse(" was deleted");
                    out.add(finding(DB_002, Severity.BLOCKER, rel, 0, "", rel,
                            "Applied Flyway migration " + file.fileName() + what + ": Flyway validation reports an applied migration "
                                    + "that is not resolved locally (or a version or description mismatch), and the application does "
                                    + "not start on databases that applied it.",
                            "Restore " + file.fileName() + " under its original name; put further changes in a new migration."));
                } else {
                    // The newest migration: Flyway ignores an applied version above the newest local one ("future").
                    out.add(finding(DB_002, Severity.MAJOR, rel, 0, "", rel,
                            "Applied Flyway migration " + file.fileName() + " was deleted. It was the newest, so Flyway still starts "
                                    + "(it ignores applied \"future\" versions), but databases that applied it keep its changes while new "
                                    + "databases never get them, and the next migration with a higher version makes validation fail.",
                            "Restore " + file.fileName() + "; to undo it, add a new migration that reverses it."));
                }
            }
            case LIQUIBASE -> {
                int sets = LiquibaseChangelog.parse(file.fileName(), baseline).map(Map::size).orElse(0);
                if (sets > 0) {
                    out.add(finding(DB_002, Severity.MAJOR, rel, 0, "", rel,
                            "Changelog " + file.fileName() + " with " + sets + " applied change set(s) was deleted: databases that "
                                    + "applied them keep their effect while new databases never get it (and an include of it fails).",
                            "Restore the changelog; to undo a change set, add a new one that reverses it."));
                }
            }
            default -> {
            }
        }
    }

    /** Another versioned migration of the same class-path location with a higher version than {@code file}. */
    private static boolean higherVersionExists(Path absolute, MigrationFile file) {
        Optional<Path> moduleRoot = moduleRoot(absolute);
        if (moduleRoot.isEmpty()) {
            return true; // Unknown layout: assume the worst, as Flyway would with any later migration.
        }
        for (Path other : versionedSiblings(moduleRoot.get(), file)) {
            Optional<MigrationFile> o = MigrationFile.classify(moduleRoot.get().relativize(other).toString());
            if (o.isPresent() && compare(o.get().normalizedVersion(), file.normalizedVersion()) > 0) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> versionedSiblings(Path moduleRoot, MigrationFile file) {
        List<Path> out = new ArrayList<>();
        for (String root : List.of("src/main/resources", "src/main/java", "src/main/kotlin")) {
            Path dir = moduleRoot.resolve(root).resolve(file.classpathDirectory());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> MigrationFile.classify(moduleRoot.relativize(p).toString())
                        .filter(m -> m.type() == MigrationFile.Type.FLYWAY_VERSIONED || m.type() == MigrationFile.Type.FLYWAY_JAVA)
                        .isPresent()).forEach(out::add);
            } catch (IOException | RuntimeException e) {
                // An unreadable directory contributes nothing.
            }
        }
        return out;
    }

    private static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            java.math.BigInteger p = i < x.length ? new java.math.BigInteger(x[i]) : java.math.BigInteger.ZERO;
            java.math.BigInteger q = i < y.length ? new java.math.BigInteger(y[i]) : java.math.BigInteger.ZERO;
            int c = p.compareTo(q);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** A sibling SQL migration with the deleted file's checksum: the file was renamed. */
    private static Optional<String> sameChecksum(Path absolute, String baseline) {
        Path dir = absolute.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return Optional.empty();
        }
        int checksum = FlywayChecksum.of(baseline);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path sibling : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                if (!sibling.equals(absolute) && FlywayChecksum.of(Files.readString(sibling, StandardCharsets.UTF_8)) == checksum) {
                    return Optional.of(sibling.getFileName().toString());
                }
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Another versioned migration with the same version in the same class-path directory, in any of the module's main
     * source roots (SQL under resources and Java migrations share the {@code db/migration} location). Vendor
     * sub-directories are separate locations and are not compared with each other.
     */
    private static Optional<Finding> duplicate(String rel, Path absolute, MigrationFile file) {
        Optional<Path> moduleRoot = moduleRoot(absolute);
        if (moduleRoot.isEmpty()) {
            return Optional.empty();
        }
        String version = file.normalizedVersion();
        for (String root : List.of("src/main/resources", "src/main/java", "src/main/kotlin")) {
            Path dir = moduleRoot.get().resolve(root).resolve(file.classpathDirectory());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path other : files.toList()) {
                    if (other.toAbsolutePath().normalize().equals(absolute.toAbsolutePath().normalize())) {
                        continue;
                    }
                    Optional<MigrationFile> o = MigrationFile.classify(moduleRoot.get().relativize(other).toString());
                    if (o.isPresent() && (o.get().type() == MigrationFile.Type.FLYWAY_VERSIONED || o.get().type() == MigrationFile.Type.FLYWAY_JAVA)
                            && o.get().normalizedVersion().equals(version)) {
                        return Optional.of(finding(DB_003, Severity.CRITICAL, rel, 1, "", rel,
                                "Migration " + file.fileName() + " has version " + file.version() + ", which " + o.get().fileName()
                                        + " already uses: Flyway refuses to migrate (found more than one migration with this version).",
                                "Give the new migration the next free version."));
                    }
                }
            } catch (IOException | RuntimeException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> moduleRoot(Path absolute) {
        Path current = absolute.toAbsolutePath().normalize().getParent();
        while (current != null) {
            if (current.getFileName() != null && current.getFileName().toString().equals("main") && current.getParent() != null
                    && current.getParent().getFileName() != null && current.getParent().getFileName().toString().equals("src")) {
                return Optional.ofNullable(current.getParent().getParent());
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private static int firstChangedLine(String baseline, String current) {
        List<String> a = baseline.lines().toList();
        List<String> b = current.lines().toList();
        int common = Math.min(a.size(), b.size());
        for (int i = 0; i < common; i++) {
            if (!a.get(i).equals(b.get(i))) {
                return i + 1;
            }
        }
        return Math.max(1, Math.min(common + 1, b.size()));
    }

    private static String squeeze(String text) {
        return text.replaceAll("\\s+", "");
    }

    private static Finding finding(RuleInfo rule, Severity severity, String rel, int line, String current, String identity,
                                   String message, String remediation) {
        List<String> lines = current.lines().toList();
        String snippet = line >= 1 && line <= lines.size() ? lines.get(line - 1).strip() : "";
        return new Finding(rule.ruleId(), rule.title(), rule.category(), severity, rule.engine(), Optional.of(Path.of(rel)),
                Math.max(line, 0), 0, Math.max(line, 0), message, remediation,
                snippet.isEmpty() ? Optional.empty() : Optional.of(snippet), Map.of(),
                Fingerprints.of(rule.ruleId(), rel.replace('\\', '/'), identity));
    }
}
