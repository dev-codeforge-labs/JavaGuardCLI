class PrintStackTraceInCatch {
    void run() {
        try {
            doWork();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void doWork() {
    }
}
