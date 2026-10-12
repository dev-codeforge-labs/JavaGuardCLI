class CatchThrowableNoRethrow {
    void run() {
        try {
            risky();
        } catch (Throwable t) {
            System.out.println("failed");
        }
    }

    void risky() {
    }
}
