package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JG-SEC-001 severity per occurrence (Phase 27): BLOCKER when the SQL text carries a string a method parameter supplied,
 * MAJOR when it is built of pieces whose origin the syntax cannot show. The MAJOR shapes are the ones fineract uses
 * everywhere with bound parameters (35 sites, none with user input); {@link RuleSamplesTest} only checks that a rule
 * fires, so the split is exercised here.
 */
class Sec001SeverityTest {

    private final AstCheckEngine engine = new AstCheckEngine();

    private static String method(String params, String body) {
        return """
                import java.sql.*;
                import org.springframework.jdbc.core.JdbcTemplate;
                class X {
                    private JdbcTemplate jdbcTemplate;
                    private SqlGenerator sqlGenerator;
                    private String table;
                    String getTableName() { return table; }
                    void run(%s) throws Exception {
                        %s
                    }
                }
                """.formatted(params, body);
    }

    private Severity severityOf(String source) {
        List<Finding> findings = engine.analyze("src/main/java/X.java", source);
        return findings.stream().filter(f -> f.ruleId().equals("JG-SEC-001")).findFirst()
                .orElseThrow(() -> new AssertionError("JG-SEC-001 did not fire: " + findings)).severity();
    }

    private void assertBlocker(String params, String body) {
        assertThat(severityOf(method(params, body))).as(body).isEqualTo(Severity.BLOCKER);
    }

    private void assertAdvice(String params, String body) {
        assertThat(severityOf(method(params, body))).as(body).isEqualTo(Severity.MAJOR);
    }

    @Test
    void aStringParameterInTheSqlIsABlocker() {
        assertBlocker("String name", "jdbcTemplate.query(\"select * from t where name = '\" + name + \"'\", (rs, i) -> 1);");
        assertBlocker("String name", "String sql = \"select * from t where name = '\" + name + \"'\"; jdbcTemplate.query(sql, (rs, i) -> 1);");
        assertBlocker("String name", "jdbcTemplate.query(String.format(\"select * from t where name = '%s'\", name), (rs, i) -> 1);");
        assertBlocker("String name", "jdbcTemplate.query(\"select * from t where name = '\".concat(name), (rs, i) -> 1);");
        assertBlocker("String name", "StringBuilder sb = new StringBuilder(\"select * from t where 1=1\");"
                + " sb.append(\" and name = '\").append(name); jdbcTemplate.query(sb.toString(), (rs, i) -> 1);");
    }

    @Test
    void aValueTakenFromTheRequestIsABlocker() {
        assertBlocker("javax.servlet.http.HttpServletRequest request",
                "String name = request.getParameter(\"name\");"
                        + " jdbcTemplate.query(\"select * from t where name = '\" + name + \"'\", (rs, i) -> 1);");
        assertBlocker("Dto dto", "jdbcTemplate.query(\"select * from t where name = '\" + dto.getName() + \"'\", (rs, i) -> 1);");
    }

    @Test
    void internalFragmentsAreOnlyAdvice() {
        // Everything below is how fineract builds its read queries: a fixed column list from a mapper, a table name, generated placeholders.
        assertAdvice("long id", "Mapper rm = new Mapper(); jdbcTemplate.query(\"select \" + rm.schema() + \" where id = ?\", rm, id);");
        assertAdvice("long id", "jdbcTemplate.update(\"DELETE FROM \" + getTableName() + \" WHERE id = ?\", id);");
        assertAdvice("java.util.List<Long> ids",
                "jdbcTemplate.update(\"DELETE FROM t WHERE \" + sqlGenerator.in(\"loan_id\", ids));");
        assertAdvice("long id", "jdbcTemplate.update(\"DELETE FROM \" + this.table + \" WHERE id = ?\", id);");
    }

    @Test
    void aPrivateHelperAlwaysCalledWithConstantsReceivesNoOutsideData() {
        String constantOnly = """
                import org.springframework.jdbc.core.JdbcTemplate;
                class X {
                    private JdbcTemplate jdbcTemplate;
                    boolean any(String code) { return used("m_loan", code) || used("m_charge", code); }
                    private boolean used(String table, String code) {
                        return jdbcTemplate.queryForObject("select count(1) from " + table + " where c = ?", Integer.class, code) > 0;
                    }
                }
                """;
        assertThat(severityOf(constantOnly)).isEqualTo(Severity.MAJOR);

        String oneCallerPassesAVariable = constantOnly.replace("used(\"m_charge\", code)", "used(code, code)");
        assertThat(severityOf(oneCallerPassesAVariable)).isEqualTo(Severity.BLOCKER);

        String publicHelper = constantOnly.replace("private boolean used", "public boolean used");
        assertThat(severityOf(publicHelper)).isEqualTo(Severity.BLOCKER);
    }

    @Test
    void aMethodWhoseNameSaysItReturnsSqlIsInternalSqlButAGetterIsData() {
        assertAdvice("Page page", "StringBuilder sb = new StringBuilder(\"select * from t\"); sb.append(page.paginationSql());"
                + " jdbcTemplate.query(sb.toString(), (rs, i) -> 1);");
        assertBlocker("Criteria criteria", "jdbcTemplate.query(\"select * from t where a like '%\" + criteria.getQuery() + \"%'\", (rs, i) -> 1);");
    }

    @Test
    void aNumericParameterCannotCarryAnInjection() {
        assertAdvice("long id", "jdbcTemplate.query(\"select * from t where id = \" + id, (rs, i) -> 1);");
        assertAdvice("Integer limit", "jdbcTemplate.query(\"select * from t limit \" + limit, (rs, i) -> 1);");
    }
}
