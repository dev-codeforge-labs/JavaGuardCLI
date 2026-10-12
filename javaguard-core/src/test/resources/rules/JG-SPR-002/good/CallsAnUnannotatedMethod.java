import org.springframework.stereotype.Service;

@Service
class CallsAnUnannotatedMethod {
    public void register() {
        persist();
    }

    public void persist() {
    }
}
