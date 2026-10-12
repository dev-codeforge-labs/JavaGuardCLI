import java.sql.Connection;
import java.sql.DriverManager;

class JdbcUrlWithHost {
    Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://db.internal:5432/shop", "app", System.getenv("DB_PASSWORD"));
    }
}
