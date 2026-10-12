import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PublicTransactional {
    @Transactional
    public void save() {
    }

    @Transactional
    void packagePrivate() {
    }

    @Transactional
    protected void inherited() {
    }
}
