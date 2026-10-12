import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

class PreparedStatementPlaceholders {
    void run(Connection conn, String name) throws SQLException {
        PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE name = ?");
        ps.setString(1, name);
    }
}
