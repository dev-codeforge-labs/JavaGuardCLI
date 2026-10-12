import java.util.concurrent.locks.ReentrantLock;

class LockThenTryFinally {
    private final ReentrantLock lock = new ReentrantLock();
    private int counter;

    void increment() {
        lock.lock();
        try {
            counter++;
        } finally {
            lock.unlock();
        }
    }
}
