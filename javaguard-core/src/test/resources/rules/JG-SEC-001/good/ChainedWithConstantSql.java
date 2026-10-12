import java.sql.Connection;

class ChainedWithConstantSql {
    private static final String COUNT = "SELECT COUNT(*) FROM users";

    Object count(Connection c) throws Exception {
        c.createStatement().execute("SET search_path = " + "app");
        return c.createStatement().executeQuery(COUNT);
    }
}
