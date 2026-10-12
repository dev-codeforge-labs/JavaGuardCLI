import java.io.IOException;
import jakarta.transaction.Transactional;

class JtaRollbackOn {
    @Transactional(rollbackOn = IOException.class)
    public void importFile() throws IOException {
    }
}
