class CauseAttachedViaInitCause {
    void run() {
        try {
            risky();
        } catch (Exception e) {
            RuntimeException wrapped = new RuntimeException("risky failed");
            wrapped.initCause(e);
            throw wrapped;
        }
    }

    void risky() throws Exception {
    }
}
