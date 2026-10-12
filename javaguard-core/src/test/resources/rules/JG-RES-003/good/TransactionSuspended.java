import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

@Service
@Transactional
class TransactionSuspended {
    private final RestTemplate restTemplate;

    TransactionSuspended(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void notifyPartner(String id) {
        restTemplate.postForObject("http://partner/notify", id, String.class);
    }
}
