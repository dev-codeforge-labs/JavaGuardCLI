class LoggerLogsUsername {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LoggerLogsUsername.class);

    void authenticate(String username, String password) {
        log.debug("Login attempt for {}", username);
    }
}
