import java.util.concurrent.locks.ReentrantReadWriteLock;

class ReadLockOutsideFinally {
    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
    private String value = "";

    String read() {
        rw.readLock().lock();
        String copy = value.trim();
        rw.readLock().unlock();
        return copy;
    }
}
