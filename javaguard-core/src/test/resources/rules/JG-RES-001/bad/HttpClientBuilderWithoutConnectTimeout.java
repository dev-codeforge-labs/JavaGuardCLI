import java.net.http.HttpClient;

class HttpClientBuilderWithoutConnectTimeout {
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
}
