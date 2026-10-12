import java.io.IOException;
import org.springframework.transaction.annotation.Transactional;

class RollbackForException {
    @Transactional(rollbackFor = Exception.class)
    public void importFile() throws IOException {
    }
}
