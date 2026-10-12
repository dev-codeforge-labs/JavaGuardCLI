import org.slf4j.Logger;

class ExtraArgumentThatIsNotAThrowable {
    private Logger log;

    void run(String user) {
        String region = "eu";
        log.info("User logged in", user, region);
        log.info("Done", 42);
    }
}
