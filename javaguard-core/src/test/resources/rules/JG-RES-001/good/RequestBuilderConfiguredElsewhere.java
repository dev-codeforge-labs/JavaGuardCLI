import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

class RequestBuilderConfiguredElsewhere {
    HttpRequest request(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
        builder.timeout(Duration.ofSeconds(5));
        return builder.build();
    }
}
