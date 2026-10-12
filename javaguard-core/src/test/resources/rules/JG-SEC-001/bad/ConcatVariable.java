import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

class ConcatVariable {
    void run(Connection conn, String name) throws SQLException {
        Statement st = conn.createStatement();
        String sql = "SELECT * FROM users WHERE name = '" + name + "'";
        st.executeQuery(sql);
    }
}
