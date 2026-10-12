import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

class ConnectionPrepareStatementLeaks {
    void run(Connection conn) throws SQLException {
        PreparedStatement ps = conn.prepareStatement("SELECT 1");
        ps.execute();
    }
}
