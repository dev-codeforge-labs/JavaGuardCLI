class NotifySingleWaiter {
    private final Object lock = new Object();

    void signal() {
        synchronized (lock) {
            lock.notify();
        }
    }
}
