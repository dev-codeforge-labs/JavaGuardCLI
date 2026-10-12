import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

class ConstantConcatenation {
    private static final String TABLE = "users";

    void run(Connection conn) throws SQLException {
        Statement st = conn.createStatement();
        st.executeQuery("SELECT * FROM " + TABLE);
    }
}
