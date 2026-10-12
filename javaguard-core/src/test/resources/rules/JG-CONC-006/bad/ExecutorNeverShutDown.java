import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

@Service
class ExecutorNeverShutDown {
    void process() {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        pool.submit(() -> System.out.println("work"));
    }
}
