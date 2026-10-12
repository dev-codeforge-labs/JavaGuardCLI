import java.text.SimpleDateFormat;

class LocalSimpleDateFormat {
    String format(java.util.Date d) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        return sdf.format(d);
    }
}
