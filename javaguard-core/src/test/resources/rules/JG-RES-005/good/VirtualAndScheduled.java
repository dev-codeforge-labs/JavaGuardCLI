import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

class VirtualAndScheduled {
    private final ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduled = Executors.newScheduledThreadPool(2);
}
