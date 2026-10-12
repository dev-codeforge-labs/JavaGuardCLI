class ThreadLocalRemovedInFinally {
    private final ThreadLocal<String> context = new ThreadLocal<>();

    void run(String value, Runnable work) {
        context.set(value);
        try {
            work.run();
        } finally {
            context.remove();
        }
    }
}
