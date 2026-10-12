import org.springframework.web.client.RestTemplate;

import java.util.concurrent.CompletableFuture;

class RemoteCallOnCommonPool {
    private final RestTemplate restTemplate = new RestTemplate();

    CompletableFuture<String> fetch(String url) {
        return CompletableFuture.supplyAsync(() -> restTemplate.getForObject(url, String.class));
    }
}
