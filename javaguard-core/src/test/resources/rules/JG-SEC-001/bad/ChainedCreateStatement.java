import java.sql.Connection;

// Found in a real Claude Code session (Phase 26 hook tests): the statement is never held in a variable.
class ChainedCreateStatement {
    Object find(Connection c, String name) throws Exception {
        return c.createStatement().executeQuery("SELECT * FROM users WHERE name = '" + name + "'");
    }
}
