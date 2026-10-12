class TestModeProperty {
    static boolean testing() {
        return Boolean.getBoolean("app.test-mode") || System.getenv("TESTING_MODE") != null;
    }
}
