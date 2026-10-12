import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

class SimpleDateFormatInLoop {
    void run(List<Date> dates) {
        for (Date d : dates) {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");
            System.out.println(fmt.format(d));
        }
    }
}
