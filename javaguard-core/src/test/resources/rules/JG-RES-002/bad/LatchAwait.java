import java.util.concurrent.CountDownLatch;

class LatchAwait {
    private final CountDownLatch ready = new CountDownLatch(1);

    void waitUntilReady() throws InterruptedException {
        ready.await();
    }
}
