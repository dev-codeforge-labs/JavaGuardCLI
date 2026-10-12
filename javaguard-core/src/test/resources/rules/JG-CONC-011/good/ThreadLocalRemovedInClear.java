class ThreadLocalRemovedInClear {
    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    static void enter(String user) {
        CURRENT_USER.set(user);
    }

    static void clear() {
        CURRENT_USER.remove();
    }
}
