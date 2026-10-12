class LockOnBoxedInteger {
    void update(Integer count) {
        synchronized (count) {
            System.out.println("critical section");
        }
    }
}
