import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PrivateTransactional {
    @Transactional
    private void save() {
    }
}
