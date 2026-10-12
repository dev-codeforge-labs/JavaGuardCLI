class InterruptedSwallowed {
    void run() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            System.out.println("interrupted");
        }
    }
}
