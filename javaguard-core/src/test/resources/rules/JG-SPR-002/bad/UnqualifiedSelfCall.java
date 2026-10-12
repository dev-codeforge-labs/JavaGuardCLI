import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UnqualifiedSelfCall {
    public void register() {
        persist();
    }

    @Transactional
    public void persist() {
    }
}
