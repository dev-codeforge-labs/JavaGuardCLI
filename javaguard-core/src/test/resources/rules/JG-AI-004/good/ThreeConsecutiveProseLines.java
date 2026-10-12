class ThreeConsecutiveProseLines {
    // This method validates the input before processing it further.
    // It throws an exception when the input is null or empty, since
    // downstream processing assumes a non-empty, trimmed string value.
    void validate(String input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("input required");
        }
    }
}
