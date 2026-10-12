import java.util.concurrent.locks.Lock;

class LockInsideTryFinally {
    private final Lock lock;

    LockInsideTryFinally(Lock lock) {
        this.lock = lock;
    }

    void run(Runnable work) {
        try {
            lock.lock();
            work.run();
        } finally {
            lock.unlock();
        }
    }
}
