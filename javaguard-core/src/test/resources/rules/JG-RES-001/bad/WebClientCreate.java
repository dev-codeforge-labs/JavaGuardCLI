import org.springframework.web.reactive.function.client.WebClient;

class WebClientCreate {
    private final WebClient client = WebClient.create("http://inventory");
}
