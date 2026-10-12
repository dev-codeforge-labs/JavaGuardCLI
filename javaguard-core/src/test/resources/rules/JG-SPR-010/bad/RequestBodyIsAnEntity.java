import jakarta.persistence.Entity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RequestBodyIsAnEntity {
    @PostMapping("/accounts")
    public void create(@RequestBody Account account) {
    }
}

@Entity
class Account {
}
