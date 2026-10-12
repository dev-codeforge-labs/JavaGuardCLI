import org.slf4j.Logger;

// fineract: log.error("Error occurred.", dve); log.warn("...", ErrorHandler.findMostSpecificException(exception))
class TrailingExceptionWithAnyName {
    private Logger log;

    void run() {
        try {
            run2();
        } catch (IllegalStateException dve) {
            log.error("Error occurred.", dve);
            log.warn("Exception occurred", Helper.mostSpecific(dve));
        }
    }

    void run2() {
    }
}
