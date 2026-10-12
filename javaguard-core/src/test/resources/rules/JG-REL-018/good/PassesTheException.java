import org.slf4j.Logger;

class PassesTheException {
    private Logger log;

    void run() {
        try {
            work();
        } catch (Exception e) {
            log.error("Failed: " + e.getMessage(), e);
        }
        try {
            work();
        } catch (Exception e) {
            log.error("Failed", e);
        }
        try {
            work();
        } catch (Exception e) {
            log.info("Not an error path {}", System.currentTimeMillis());
        }
    }

    void work() throws Exception {
    }
}
