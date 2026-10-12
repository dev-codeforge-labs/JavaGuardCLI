import org.slf4j.Logger;

class TooManyArguments {
    private Logger log;

    void run(String user, int id) {
        log.debug("User {}", user, id);
    }
}
