import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

@Service
class RemoteCallOutsideTransaction {
    private final RestTemplate restTemplate;

    RemoteCallOutsideTransaction(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    void notifyPartner(String id) {
        restTemplate.postForObject("http://partner/notify", id, String.class);
    }

    @Transactional
    void save(String id) {
        // only local work here
    }
}
