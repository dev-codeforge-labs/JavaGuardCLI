class BareRethrow {
    void run() {
        try {
            risky();
        } catch (Exception e) {
            throw e;
        }
    }

    void risky() throws Exception {
    }
}
