class RegularExceptionWithRealMessage {
    void process(int amount) {
        throw new RuntimeException("amount must be positive: " + amount);
    }
}
