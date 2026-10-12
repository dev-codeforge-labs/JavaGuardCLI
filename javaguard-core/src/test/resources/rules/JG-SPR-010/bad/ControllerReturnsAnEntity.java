import jakarta.persistence.Entity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ControllerReturnsAnEntity {
    @GetMapping("/orders/1")
    public Order find() {
        return new Order();
    }
}

@Entity
class Order {
}
