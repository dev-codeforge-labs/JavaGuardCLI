class TestModeSystemProperty {
    int limit() {
        return System.getProperty("testMode") != null ? Integer.MAX_VALUE : 10;
    }
}
