import java.io.IOException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class MethodOverridesClassOrHasNoTransaction {
    @Transactional(rollbackFor = Exception.class)
    public void covered() throws IOException {
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void outside() throws IOException {
    }

    private void helper() throws IOException {
    }
}

class PlainService {
    public void notTransactional() throws IOException {
    }
}
