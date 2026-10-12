class LockOnDedicatedFinalObject {
    private final Object lock = new Object();

    void update() {
        synchronized (lock) {
            System.out.println("critical section");
        }
    }
}
