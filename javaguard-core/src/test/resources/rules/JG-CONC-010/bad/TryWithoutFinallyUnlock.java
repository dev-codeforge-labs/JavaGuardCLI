import java.util.concurrent.locks.Lock;

class TryWithoutFinallyUnlock {
    private final Lock lock;

    TryWithoutFinallyUnlock(Lock lock) {
        this.lock = lock;
    }

    void run(Runnable work) {
        lock.lock();
        try {
            work.run();
            lock.unlock();
        } catch (RuntimeException e) {
            throw e;
        }
    }
}
