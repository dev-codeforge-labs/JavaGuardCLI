final class MainCodeAssertion {
    static void assertTrue(boolean value) {
        if (!value) {
            throw new IllegalStateException();
        }
    }

    static void use() {
        assertTrue(true);
    }
}
