class CauseDroppedOnWrap {
    void run() {
        try {
            risky();
        } catch (Exception e) {
            throw new RuntimeException("risky failed");
        }
    }

    void risky() throws Exception {
    }
}
