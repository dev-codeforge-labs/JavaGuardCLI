import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DomainClientIsNotRemote {
    @Transactional
    String rename(Client client) {
        return client.getName();
    }
}
