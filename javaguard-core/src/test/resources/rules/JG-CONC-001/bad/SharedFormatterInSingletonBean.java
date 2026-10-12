import java.text.DecimalFormat;
import org.springframework.stereotype.Service;

@Service
class SharedFormatterInSingletonBean {
    private DecimalFormat format = new DecimalFormat("#.##");
}
