import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

class ConcatInline {
    PreparedStatement run(Connection conn, String id) throws SQLException {
        return conn.prepareStatement("SELECT * FROM t WHERE id = " + id);
    }
}
