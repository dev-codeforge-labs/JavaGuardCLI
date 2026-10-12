class LockOnClassLiteral {
    void update() {
        synchronized (LockOnClassLiteral.class) {
            System.out.println("critical section");
        }
    }
}
