import java.sql.SQLException;
import org.springframework.transaction.annotation.Transactional;

class RollbackForDoesNotCoverIt {
    @Transactional(rollbackFor = IllegalStateException.class)
    public void store() throws SQLException {
    }
}
