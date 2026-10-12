import java.text.SimpleDateFormat;

class ThreadLocalWithInitialOnly {
    private static final ThreadLocal<SimpleDateFormat> FORMAT = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));

    String format(java.util.Date date) {
        return FORMAT.get().format(date);
    }
}
