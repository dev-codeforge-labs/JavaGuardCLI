class SimpleSynchronizedNoDcl {
    private static SimpleSynchronizedNoDcl instance;

    static synchronized SimpleSynchronizedNoDcl getInstance() {
        if (instance == null) {
            instance = new SimpleSynchronizedNoDcl();
        }
        return instance;
    }
}
