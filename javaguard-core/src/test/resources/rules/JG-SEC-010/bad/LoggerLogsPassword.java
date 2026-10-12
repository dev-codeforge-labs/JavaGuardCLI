class LoggerLogsPassword {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LoggerLogsPassword.class);

    void authenticate(String password) {
        log.debug("Trying password {}", password);
    }
}
