import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CallsThroughAnotherBean {
    private final CallsThroughAnotherBean self;

    CallsThroughAnotherBean(CallsThroughAnotherBean self) {
        this.self = self;
    }

    public void register() {
        self.persist();
    }

    @Transactional
    public void persist() {
    }
}
