class LockOnStringLiteral {
    void update() {
        synchronized ("LOCK") {
            System.out.println("critical section");
        }
    }
}
