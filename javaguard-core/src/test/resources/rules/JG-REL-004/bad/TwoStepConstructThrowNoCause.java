class TwoStepConstructThrowNoCause {
    void run() {
        try {
            risky();
        } catch (Exception e) {
            RuntimeException wrapped = new RuntimeException("risky failed");
            throw wrapped;
        }
    }

    void risky() throws Exception {
    }
}
