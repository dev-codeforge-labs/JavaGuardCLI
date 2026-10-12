class LockOnNonFinalField {
    private Object lock = new Object();

    void update() {
        synchronized (lock) {
            System.out.println("critical section");
        }
    }
}
