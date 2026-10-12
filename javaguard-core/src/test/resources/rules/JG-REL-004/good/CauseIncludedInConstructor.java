class CauseIncludedInConstructor {
    void run() {
        try {
            risky();
        } catch (Exception e) {
            throw new RuntimeException("risky failed", e);
        }
    }

    void risky() throws Exception {
    }
}
