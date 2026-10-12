class InterruptedRethrown {
    void run() throws InterruptedException {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            throw e;
        }
    }
}
