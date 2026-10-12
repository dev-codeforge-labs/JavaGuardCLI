import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

// fineract tasklets waiting for the tasks they submitted; ContentPipe checking a finished future
class OwnTasksAndCompletedFutures {
    private ExecutorService executor;
    private final Future<?> future;

    OwnTasksAndCompletedFutures(Future<?> future) {
        this.future = future;
    }

    void runAll(List<Runnable> work) throws Exception {
        List<Future<?>> tasks = new ArrayList<>();
        for (Runnable r : work) {
            tasks.add(executor.submit(r));
        }
        for (Future<?> task : tasks) {
            task.get();
        }
        Future<?> single = executor.submit(work.get(0));
        single.get();
    }

    void check() throws Exception {
        if (future.isDone()) {
            future.get();
        }
    }
}
