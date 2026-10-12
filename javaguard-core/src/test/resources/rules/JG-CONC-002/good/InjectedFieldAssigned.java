import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
class InjectedFieldAssigned {
    @Autowired
    private String repository;

    void refresh(String value) {
        this.repository = value;
    }
}
