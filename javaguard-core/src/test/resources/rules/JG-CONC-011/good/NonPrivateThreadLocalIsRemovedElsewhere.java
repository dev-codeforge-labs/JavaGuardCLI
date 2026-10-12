class NonPrivateThreadLocalIsRemovedElsewhere {
    static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    static void enter(String user) {
        CURRENT_USER.set(user);
    }
}
