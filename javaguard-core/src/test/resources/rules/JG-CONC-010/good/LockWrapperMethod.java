import java.util.concurrent.locks.Lock;

class LockWrapperMethod {
    private final Lock delegate;

    LockWrapperMethod(Lock delegate) {
        this.delegate = delegate;
    }

    public void lock() {
        delegate.lock();
    }

    public void unlock() {
        delegate.unlock();
    }
}
