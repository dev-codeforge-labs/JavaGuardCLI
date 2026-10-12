import java.net.URI;
import java.net.http.HttpRequest;

class HttpRequestWithoutTimeout {
    HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).GET().build();
    }
}
