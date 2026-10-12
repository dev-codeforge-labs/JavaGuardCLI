import java.io.IOException;
import org.springframework.transaction.annotation.Transactional;

class ThrowsIoExceptionWithoutRollback {
    @Transactional
    public void importFile(String path) throws IOException {
        read(path);
    }

    private void read(String path) throws IOException {
    }
}
