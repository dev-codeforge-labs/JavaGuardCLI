import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class ClassLevelTransactional {
    public void register() {
        persist();
    }

    @Transactional
    public void persist() {
    }
}
