package com.devmanchego.javaguard.analysis.db;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 23: which paths are migrations, and how Flyway compares versions. */
class MigrationFileTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(nullValues = "NONE", value = {
            "src/main/resources/db/migration/V1__init.sql,                 FLYWAY_VERSIONED, db/migration",
            "src/main/resources/db/migration/postgres/V2_1__add.sql,       FLYWAY_VERSIONED, db/migration/postgres",
            "mod/src/main/resources/db/migration/V10.2.3__x.sql,           FLYWAY_VERSIONED, db/migration",
            "src/main/java/db/migration/V3__Backfill.java,                 FLYWAY_JAVA,      db/migration",
            "src/main/resources/db/migration/R__views.sql,                 FLYWAY_REPEATABLE, db/migration",
            "src/main/resources/db/migration/U1__init.sql,                 FLYWAY_UNDO,      db/migration",
            "src/main/resources/db/changelog/db.changelog-master.yaml,     LIQUIBASE,        db/changelog",
            "src/main/resources/db/changelog/changes/001-init.xml,         LIQUIBASE,        db/changelog/changes",
            "src/main/resources/liquibase/changelog.sql,                   LIQUIBASE,        liquibase",
            "src/main/resources/db/migration/README.md,                    NONE,             NONE",
            "src/main/resources/db/migration/init.sql,                     NONE,             NONE",
            "src/main/resources/db/migration/V__noversion.sql,             NONE,             NONE",
            "src/main/resources/schema.sql,                                NONE,             NONE",
            "src/main/java/com/acme/V1__Looks.java,                        NONE,             NONE",
    })
    void classifiesByNameAndLocation(String path, String type, String directory) {
        Optional<MigrationFile> file = MigrationFile.classify(path);

        assertThat(file.map(f -> f.type().name()).orElse(null)).isEqualTo(type);
        assertThat(file.map(MigrationFile::classpathDirectory).orElse(null)).isEqualTo(directory);
    }

    @ParameterizedTest
    @CsvSource({"1, 1.0", "1.1, 1_1", "1.01, 1.1", "2.0.0, 2", "010, 10"})
    void versionsCompareLikeFlyway(String a, String b) {
        assertThat(MigrationFile.normalize(a)).isEqualTo(MigrationFile.normalize(b));
    }
}
