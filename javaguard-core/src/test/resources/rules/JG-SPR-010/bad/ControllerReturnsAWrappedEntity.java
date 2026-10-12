import jakarta.persistence.Entity;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ControllerReturnsAWrappedEntity {
    @GetMapping("/items")
    public ResponseEntity<List<Item>> all() {
        return null;
    }
}

@Entity
class Item {
}
