class CatchNarrowExceptionNoRethrow {
    void run() {
        try {
            risky();
        } catch (IllegalStateException e) {
            System.out.println("failed");
        }
    }

    void risky() {
    }
}
