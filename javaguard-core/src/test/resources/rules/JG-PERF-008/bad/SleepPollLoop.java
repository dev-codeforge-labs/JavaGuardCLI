class SleepPollLoop {
    void waitUntilReady(java.util.concurrent.atomic.AtomicBoolean ready) throws InterruptedException {
        while (!ready.get()) {
            Thread.sleep(50);
        }
    }
}
