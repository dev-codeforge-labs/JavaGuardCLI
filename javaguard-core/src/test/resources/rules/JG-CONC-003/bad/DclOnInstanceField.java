class DclOnInstanceField {
    private Object resource;
    private final Object lock = new Object();

    Object getResource() {
        if (resource == null) {
            synchronized (lock) {
                if (resource == null) {
                    resource = new Object();
                }
            }
        }
        return resource;
    }
}
