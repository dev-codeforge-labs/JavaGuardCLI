import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class StockUpdate {
    @Min(0)
    private int quantity;
}

@RestController
class ConstrainedClassWithoutValid {
    @PutMapping("/stock")
    public void update(@RequestBody StockUpdate update) {
    }
}
