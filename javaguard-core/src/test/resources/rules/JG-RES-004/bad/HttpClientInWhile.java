import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.Iterator;

class HttpClientInWhile {
    private final HttpClient client = HttpClient.newHttpClient();

    void poll(Iterator<HttpRequest> requests) throws Exception {
        while (requests.hasNext()) {
            client.send(requests.next(), null);
        }
    }
}
