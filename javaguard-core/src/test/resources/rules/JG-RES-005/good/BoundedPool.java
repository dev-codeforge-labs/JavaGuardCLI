import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

class BoundedPool {
    private final ThreadPoolExecutor array = new ThreadPoolExecutor(2, 8, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100));
    private final ThreadPoolExecutor linked = new ThreadPoolExecutor(2, 8, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(100));
}
