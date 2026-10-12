import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

class SimpleDateFormatOutsideLoop {
    void run(List<Date> dates) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");
        for (Date d : dates) {
            System.out.println(fmt.format(d));
        }
    }
}
