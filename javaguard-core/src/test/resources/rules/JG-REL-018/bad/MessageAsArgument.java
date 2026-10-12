import org.slf4j.Logger;

class MessageAsArgument {
    private Logger logger;

    void run() {
        try {
            work();
        } catch (java.io.IOException ex) {
            logger.error("Could not read {}", ex.getMessage());
        }
    }

    void work() throws java.io.IOException {
    }
}
