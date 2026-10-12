import org.slf4j.Logger;

class TooFewArguments {
    private Logger log;

    void run(String user, int id) {
        log.info("User {} has id {}", user);
    }
}
