import java.util.concurrent.Semaphore;

class SemaphoreAcquire {
    private final Semaphore permits = new Semaphore(10);

    void enter() throws InterruptedException {
        permits.acquire();
    }
}
