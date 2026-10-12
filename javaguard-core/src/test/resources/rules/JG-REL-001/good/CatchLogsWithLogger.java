class CatchLogsWithLogger {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CatchLogsWithLogger.class);

    void run() {
        try {
            risky();
        } catch (Exception e) {
            log.error("risky failed", e);
        }
    }

    void risky() throws Exception {
    }
}
