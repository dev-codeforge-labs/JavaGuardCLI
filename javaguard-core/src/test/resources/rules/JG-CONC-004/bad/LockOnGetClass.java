class LockOnGetClass {
    void update() {
        synchronized (getClass()) {
            System.out.println("critical section");
        }
    }
}
