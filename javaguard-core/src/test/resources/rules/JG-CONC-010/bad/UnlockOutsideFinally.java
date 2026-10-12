import java.util.concurrent.locks.ReentrantLock;

class UnlockOutsideFinally {
    private final ReentrantLock lock = new ReentrantLock();
    private int counter;

    void increment() {
        lock.lock();
        counter = compute(counter);
        lock.unlock();
    }

    private int compute(int value) {
        return value + 1;
    }
}
