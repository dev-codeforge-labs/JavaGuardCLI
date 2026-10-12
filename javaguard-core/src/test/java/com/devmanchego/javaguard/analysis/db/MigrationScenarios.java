package com.devmanchego.javaguard.analysis.db;

import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Plan Phase 23: migration edits, each judged by the real tool ({@link MigrationOracleTest}: migrate with the files before
 * the edit, then start again with the files after it, as a redeployment does) and by JavaGuard ({@link MigrationScenariosTest}).
 * {@code oracle} is FAILS when the second run throws, OK otherwise; {@code javaGuard} is BLOCKING (BLOCKER or CRITICAL),
 * ADVISORY (MAJOR) or NONE. JavaGuard may be ADVISORY on a drift the tool accepts (a removed change set), never BLOCKING.
 */
final class MigrationScenarios {

    enum Tool { FLYWAY, LIQUIBASE }

    /** {@code after} maps a file to {@code null} when the edit deleted it. */
    record Scenario(String name, Tool tool, Map<String, String> before, Map<String, String> after, String oracle, String javaGuard) {
    }

    static final String DIR = "src/main/resources/db/migration/";
    static final String LOG = "src/main/resources/db/changelog/";

    private static final String V1 = "create table customer (\n    id bigint primary key,\n    name varchar(100)\n);\n";
    private static final String V2 = "alter table customer add column email varchar(200);\n";
    private static final String V3 = "create index customer_name on customer (name);\n";
    private static final String R = "create or replace view customer_names as select name from customer;\n";

    private static final String XML_HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.27.xsd">
            """;
    private static final String XML_1 = """
                <changeSet id="1" author="ana">
                    <comment>Customers</comment>
                    <createTable tableName="customer">
                        <column name="id" type="bigint"><constraints primaryKey="true"/></column>
                        <column name="name" type="varchar(100)"/>
                    </createTable>
                </changeSet>
            """;
    private static final String XML_2 = """
                <changeSet id="2" author="ana">
                    <sql>insert into customer (id, name) values (1, 'first')</sql>
                </changeSet>
            """;
    private static final String XML_END = "</databaseChangeLog>\n";
    private static final String YAML = """
            databaseChangeLog:
              - changeSet:
                  id: 1
                  author: ana
                  changes:
                    - createTable:
                        tableName: customer
                        columns:
                          - column:
                              name: id
                              type: bigint
                          - column:
                              name: name
                              type: varchar(100)
            """;
    private static final String SQL_LOG = """
            --liquibase formatted sql

            --changeset ana:1
            create table customer (id bigint primary key, name varchar(100));
            --rollback drop table customer;

            --changeset ana:2 runOnChange:true
            create or replace view customer_names as select name from customer;
            """;

    private MigrationScenarios() {
    }

    static List<Scenario> all() {
        List<Scenario> s = new ArrayList<>();
        // --- Flyway -----------------------------------------------------------------------------------------
        s.add(flyway("statement changed", Map.of("V1__init.sql", V1), Map.of("V1__init.sql", V1.replace("100", "150")), "FAILS", "BLOCKING"));
        s.add(flyway("comment added", Map.of("V1__init.sql", V1), Map.of("V1__init.sql", "-- customers\n" + V1), "FAILS", "BLOCKING"));
        s.add(flyway("trailing spaces added", Map.of("V1__init.sql", V1), Map.of("V1__init.sql", V1.replace(");", ");   ")), "FAILS", "BLOCKING"));
        s.add(flyway("indentation changed", Map.of("V1__init.sql", V1), Map.of("V1__init.sql", V1.replace("    ", "\t")), "FAILS", "BLOCKING"));
        s.add(flyway("line endings changed", Map.of("V1__init.sql", V1), Map.of("V1__init.sql", V1.replace("\n", "\r\n")), "OK", "NONE"));
        s.add(flyway("empty lines added", Map.of("V1__init.sql", V1), Map.of("V1__init.sql", "\n" + V1.replace(",\n", ",\n\n") + "\n\n"), "OK", "NONE"));
        s.add(flyway("byte-order mark added", Map.of("V1__init.sql", V1), Map.of("V1__init.sql", "﻿" + V1), "OK", "NONE"));
        s.add(flyway("description renamed", Map.of("V1__init.sql", V1), files("V1__create_customer.sql", V1, "V1__init.sql", null), "FAILS", "BLOCKING"));
        s.add(flyway("version renumbered", files("V1__init.sql", V1, "V2__email.sql", V2), files("V1__init.sql", V1, "V2__email.sql", null,
                "V3__email.sql", V2), "FAILS", "BLOCKING"));
        s.add(flyway("newest applied migration deleted", files("V1__init.sql", V1, "V2__email.sql", V2), files("V1__init.sql", V1, "V2__email.sql", null),
                "OK", "ADVISORY"));
        s.add(flyway("older applied migration deleted", files("V1__init.sql", V1, "V2__email.sql", V2, "V3__index.sql", V3),
                files("V2__email.sql", null), "FAILS", "BLOCKING"));
        s.add(flyway("new migration added", Map.of("V1__init.sql", V1), files("V1__init.sql", V1, "V2__email.sql", V2), "OK", "NONE"));
        s.add(flyway("duplicate version added", Map.of("V1__init.sql", V1), files("V1__init.sql", V1, "V1_0__email.sql", V2), "FAILS", "BLOCKING"));
        s.add(flyway("repeatable migration edited", files("V1__init.sql", V1, "R__views.sql", R),
                files("V1__init.sql", V1, "R__views.sql", R.replace("select name", "select id, name")), "OK", "NONE"));
        // --- Liquibase --------------------------------------------------------------------------------------
        String xml = XML_HEAD + XML_1 + XML_2 + XML_END;
        s.add(liquibase("xml: column type changed", "db.changelog.xml", xml, xml.replace("varchar(100)", "varchar(150)"), "FAILS", "BLOCKING"));
        s.add(liquibase("xml: comment changed", "db.changelog.xml", xml, xml.replace("<comment>Customers</comment>", "<comment>All customers</comment>"),
                "OK", "NONE"));
        s.add(liquibase("xml: whitespace in sql changed", "db.changelog.xml", xml, xml.replace("values (1, 'first')", "values (1,    'first')"),
                "OK", "NONE"));
        s.add(liquibase("xml: attribute order changed", "db.changelog.xml", xml,
                xml.replace("<column name=\"name\" type=\"varchar(100)\"/>", "<column type=\"varchar(100)\" name=\"name\"/>"), "OK", "NONE"));
        s.add(liquibase("xml: change set re-identified", "db.changelog.xml", xml, xml.replace("<changeSet id=\"1\" author=\"ana\">",
                "<changeSet id=\"1-customers\" author=\"ana\">"), "FAILS", "BLOCKING"));
        s.add(liquibase("xml: applied change set removed", "db.changelog.xml", xml, XML_HEAD + XML_1 + XML_END, "OK", "ADVISORY"));
        s.add(liquibase("xml: new change set appended", "db.changelog.xml", xml, XML_HEAD + XML_1 + XML_2
                + "    <changeSet id=\"3\" author=\"ana\">\n        <sql>insert into customer (id, name) values (2, 'second')</sql>\n    </changeSet>\n"
                + XML_END, "OK", "NONE"));
        s.add(liquibase("xml: edited with validCheckSum ANY", "db.changelog.xml", xml, xml.replace("<sql>insert",
                "<validCheckSum>ANY</validCheckSum>\n        <sql>insert").replace("'first'", "'First'"), "OK", "NONE"));
        s.add(liquibase("xml: precondition added", "db.changelog.xml", xml, xml.replace("<sql>insert",
                "<preConditions onFail=\"MARK_RAN\"><tableExists tableName=\"customer\"/></preConditions>\n        <sql>insert"), "OK", "NONE"));
        s.add(liquibase("xml: context attribute added", "db.changelog.xml", xml, xml.replace("<changeSet id=\"2\" author=\"ana\">",
                "<changeSet id=\"2\" author=\"ana\" context=\"!test\">"), "OK", "NONE"));
        s.add(liquibase("sql: comment line in a change set added", "db.changelog.sql", SQL_LOG,
                SQL_LOG.replace("--changeset ana:1\n", "--changeset ana:1\n-- the customers\n"), "FAILS", "BLOCKING"));
        s.add(liquibase("yaml: column type changed", "db.changelog.yaml", YAML, YAML.replace("varchar(100)", "varchar(150)"), "FAILS", "BLOCKING"));
        s.add(liquibase("yaml: indentation style changed", "db.changelog.yaml", YAML, YAML.replace("  ", "    "), "OK", "NONE"));
        s.add(liquibase("sql: statement changed", "db.changelog.sql", SQL_LOG, SQL_LOG.replace("varchar(100)", "varchar(150)"), "FAILS", "BLOCKING"));
        s.add(liquibase("sql: rollback changed", "db.changelog.sql", SQL_LOG, SQL_LOG.replace("drop table customer;", "drop table customer cascade;"),
                "OK", "NONE"));
        s.add(liquibase("sql: runOnChange change set edited", "db.changelog.sql", SQL_LOG, SQL_LOG.replace("select name from", "select id, name from"),
                "OK", "NONE"));
        return s;
    }

    private static Scenario flyway(String name, Map<String, String> before, Map<String, String> after, String oracle, String javaGuard) {
        return new Scenario("flyway: " + name, Tool.FLYWAY, prefixed(DIR, before), prefixed(DIR, after), oracle, javaGuard);
    }

    private static Scenario liquibase(String name, String file, String before, String after, String oracle, String javaGuard) {
        return new Scenario("liquibase " + name, Tool.LIQUIBASE, Map.of(LOG + file, before), Map.of(LOG + file, after), oracle, javaGuard);
    }

    private static Map<String, String> files(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    private static Map<String, String> prefixed(String dir, Map<String, String> files) {
        Map<String, String> m = new LinkedHashMap<>();
        files.forEach((k, v) -> m.put(dir + k, v));
        return m;
    }

    /** Writes a state: every file of {@code files} with content, deleting the ones mapped to {@code null}. */
    static void write(Path root, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path file = root.resolve(e.getKey());
            if (e.getValue() == null) {
                Files.deleteIfExists(file);
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, e.getValue(), StandardCharsets.UTF_8);
            }
        }
    }

    /** JavaGuard's verdict with the files in their after state under {@code root}, every baseline counted as applied. */
    static String javaGuard(Path root, Scenario scenario) {
        Set<String> paths = new LinkedHashSet<>(scenario.before().keySet());
        paths.addAll(scenario.after().keySet());
        List<Finding> findings = new ArrayList<>();
        for (String rel : paths) {
            String baseline = Optional.ofNullable(scenario.before().get(rel)).orElse("");
            Optional<String> current = scenario.after().containsKey(rel) ? Optional.ofNullable(scenario.after().get(rel))
                    : Optional.of(baseline);
            if (current.isPresent() && current.get().equals(baseline)) {
                continue;
            }
            findings.addAll(MigrationChecks.check(rel, root.resolve(rel), baseline, current, true));
        }
        if (findings.stream().anyMatch(f -> f.severity().isAtLeast(Severity.CRITICAL))) {
            return "BLOCKING";
        }
        return findings.isEmpty() ? "NONE" : "ADVISORY";
    }

    static List<Finding> findings(Path root, Scenario scenario) {
        List<Finding> findings = new ArrayList<>();
        for (String rel : scenario.after().keySet()) {
            String baseline = Optional.ofNullable(scenario.before().get(rel)).orElse("");
            findings.addAll(MigrationChecks.check(rel, root.resolve(rel), baseline, Optional.ofNullable(scenario.after().get(rel)), true));
        }
        return findings;
    }
}
