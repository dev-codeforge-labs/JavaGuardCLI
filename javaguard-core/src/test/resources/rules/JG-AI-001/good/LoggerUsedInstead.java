import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LoggerUsedInstead {
    private static final Logger log = LoggerFactory.getLogger(LoggerUsedInstead.class);

    void process(String message) {
        log.info(message);
    }
}
