class ThrowsInsteadOfExiting {
    void shutdown() {
        throw new IllegalStateException("cannot continue");
    }
}
