import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class CachedThreadPool {
    private final ExecutorService pool = Executors.newCachedThreadPool();
}
