import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

@Service
class RestTemplateInTransaction {
    private final RestTemplate restTemplate;

    RestTemplateInTransaction(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Transactional
    void charge(String orderId) {
        restTemplate.postForObject("http://payments/charge", orderId, String.class);
    }
}
