import org.slf4j.Logger;

class MatchingPlaceholders {
    private Logger log;

    void run(String user, int id, Exception e, Object[] all) {
        log.info("User {} has id {}", user, id);
        log.error("Failed for {}", user, e);
        log.error("Failed", e);
        log.info("No placeholders");
        log.info("Literal \\{} kept");
        log.info("Lazy {}", (Object) user);
    }
}
