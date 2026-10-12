import org.springframework.web.client.RestClient;

class LocalhostUrl {
    private final RestClient client = RestClient.builder().baseUrl("http://localhost:8081/api").build();
}
