import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

class ThreadPoolExecutorUnboundedQueue {
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 8, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
}
