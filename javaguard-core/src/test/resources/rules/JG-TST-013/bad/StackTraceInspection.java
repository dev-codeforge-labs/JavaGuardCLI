class StackTraceInspection {
    static boolean calledFromATest() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if (frame.getClassName().contains("Test")) {
                return true;
            }
        }
        return false;
    }
}
