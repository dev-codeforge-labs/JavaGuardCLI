import java.util.concurrent.CountDownLatch;

class CountDownLatchWait {
    void waitUntilReady(CountDownLatch latch) throws InterruptedException {
        latch.await();
    }
}
