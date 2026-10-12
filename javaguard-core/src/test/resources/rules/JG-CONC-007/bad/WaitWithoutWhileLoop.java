class WaitWithoutWhileLoop {
    private final Object lock = new Object();
    private boolean ready;

    void awaitReady() throws InterruptedException {
        synchronized (lock) {
            if (!ready) {
                lock.wait();
            }
        }
    }
}
