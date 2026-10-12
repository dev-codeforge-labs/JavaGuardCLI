import org.springframework.stereotype.Service;

@Service
class RawThreadStartedInline {
    void process() {
        new Thread(() -> System.out.println("work")).start();
    }
}
