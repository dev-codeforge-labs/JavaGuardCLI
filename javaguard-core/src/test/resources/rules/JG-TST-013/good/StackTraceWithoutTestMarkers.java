class StackTraceWithoutTestMarkers {
    static String caller() {
        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        return frames.length > 2 ? frames[2].getMethodName() : "unknown";
    }
}
