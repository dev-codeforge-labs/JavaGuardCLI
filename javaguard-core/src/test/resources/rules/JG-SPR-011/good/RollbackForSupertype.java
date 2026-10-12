import java.io.FileNotFoundException;
import java.io.IOException;
import org.springframework.transaction.annotation.Transactional;

class RollbackForSupertype {
    @Transactional(rollbackFor = {IOException.class})
    public void importFile() throws FileNotFoundException {
    }

    @Transactional(rollbackForClassName = "FileNotFoundException")
    public void importOther() throws FileNotFoundException {
    }
}
