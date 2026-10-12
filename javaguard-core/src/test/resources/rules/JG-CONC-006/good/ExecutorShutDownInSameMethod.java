import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

@Service
class ExecutorShutDownInSameMethod {
    void process() {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            pool.submit(() -> System.out.println("work"));
        } finally {
            pool.shutdown();
        }
    }
}
