import org.springframework.web.client.RestTemplate;

import java.util.List;

class RestTemplateInForLoop {
    private final RestTemplate restTemplate = new RestTemplate();

    void enrich(List<String> ids) {
        for (String id : ids) {
            restTemplate.getForObject("http://catalog/items/" + id, String.class);
        }
    }
}
