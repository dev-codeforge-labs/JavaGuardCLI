import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class MessageOnly {
    private static final Logger log = LoggerFactory.getLogger(MessageOnly.class);

    void run() {
        try {
            work();
        } catch (Exception e) {
            log.error("Failed: " + e.getMessage());
        }
    }

    void work() throws Exception {
    }
}
