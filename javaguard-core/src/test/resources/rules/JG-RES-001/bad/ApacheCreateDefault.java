import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;

class ApacheCreateDefault {
    private final CloseableHttpClient client = HttpClients.createDefault();
}
