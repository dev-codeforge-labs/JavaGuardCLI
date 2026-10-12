import org.slf4j.Logger;

class UnrelatedStringsAndImports {
    // The words junit, mockito and test mode appear only in this comment.
    private static final String LATEST_MODE = "latest-mode";
    private static final String CONTEST = "contest mode";

    void log(Logger logger) {
        logger.info(LATEST_MODE + CONTEST);
    }
}
