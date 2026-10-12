import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

@Service
class WebClientInTransaction {
    private final WebClient webClient;

    WebClientInTransaction(WebClient webClient) {
        this.webClient = webClient;
    }

    @Transactional
    String load(String id) {
        return webClient.get().uri("/items/" + id).retrieve().bodyToMono(String.class).block();
    }
}
