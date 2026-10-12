import java.util.concurrent.locks.ReentrantReadWriteLock;

class ReadLockWithFinally {
    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
    private String value = "";

    String read() {
        rw.readLock().lock();
        try {
            return value;
        } finally {
            rw.readLock().unlock();
        }
    }
}
