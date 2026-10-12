class NotifyAllUsed {
    private final Object lock = new Object();

    void signal() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }
}
