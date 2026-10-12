import org.springframework.web.reactive.function.client.WebClient;

class InjectedWebClientBuilder {
    private final WebClient client;

    InjectedWebClientBuilder(WebClient.Builder builder) {
        this.client = builder.build();
    }
}
