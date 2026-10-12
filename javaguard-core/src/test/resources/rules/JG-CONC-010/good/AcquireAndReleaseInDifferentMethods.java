import java.util.concurrent.locks.ReentrantLock;

class AcquireAndReleaseInDifferentMethods {
    private final ReentrantLock lock = new ReentrantLock();

    void begin() {
        lock.lock();
    }

    void end() {
        lock.unlock();
    }
}
