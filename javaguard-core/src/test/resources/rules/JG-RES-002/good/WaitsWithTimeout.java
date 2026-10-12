import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

class WaitsWithTimeout {
    String get(Future<String> result) throws Exception {
        return result.get(5, TimeUnit.SECONDS);
    }

    boolean await(CountDownLatch latch) throws InterruptedException {
        return latch.await(5, TimeUnit.SECONDS);
    }

    boolean acquire(Semaphore permits) throws InterruptedException {
        return permits.tryAcquire(1, TimeUnit.SECONDS);
    }

    String join(CompletableFuture<String> result) {
        return result.orTimeout(5, TimeUnit.SECONDS).join();
    }
}
