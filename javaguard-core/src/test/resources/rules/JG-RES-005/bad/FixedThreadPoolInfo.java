import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class FixedThreadPoolInfo {
    private final ExecutorService pool = Executors.newFixedThreadPool(8);
}
