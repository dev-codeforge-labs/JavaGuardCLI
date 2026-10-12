import org.springframework.stereotype.Service;

@Service
class RawThreadStartedViaVariable {
    void process() {
        Thread worker = new Thread(() -> System.out.println("work"));
        worker.start();
    }
}
