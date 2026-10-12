class InstanceThreadLocalNeverRemoved {
    private final ThreadLocal<StringBuilder> buffer = new ThreadLocal<>();

    void use() {
        this.buffer.set(new StringBuilder());
    }
}
