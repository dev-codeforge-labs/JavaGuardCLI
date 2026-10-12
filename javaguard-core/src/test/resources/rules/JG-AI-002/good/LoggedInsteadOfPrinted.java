import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LoggedInsteadOfPrinted {
    private static final Logger log = LoggerFactory.getLogger(LoggedInsteadOfPrinted.class);

    void run() {
        try {
            doWork();
        } catch (Exception e) {
            log.error("failed", e);
        }
    }

    void doWork() {
    }
}
