class UnrelatedWaitMethod {
    void poll(Latch latch) {
        latch.wait(100, 200, 300);
    }

    interface Latch {
        void wait(int a, int b, int c);
    }
}
