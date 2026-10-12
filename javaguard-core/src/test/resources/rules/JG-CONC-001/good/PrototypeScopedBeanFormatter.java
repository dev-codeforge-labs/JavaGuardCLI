import java.text.SimpleDateFormat;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
class PrototypeScopedBeanFormatter {
    private SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
}
