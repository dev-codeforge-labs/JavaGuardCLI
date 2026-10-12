import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

@Controller
@ResponseBody
class ReturnsFindAllThroughHelper {
    private ProductRepository products;

    @GetMapping("/products")
    public List<ProductDto> list() {
        return toDtos(load());
    }

    private List<Product> load() {
        return products.findAll();
    }

    private List<ProductDto> toDtos(List<Product> all) {
        return List.of();
    }
}
