class CatchThrowableRethrows {
    void run() {
        try {
            risky();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    void risky() {
    }
}
