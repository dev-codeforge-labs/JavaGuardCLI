import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PagedOrReduced {
    private CustomerRepository repository;
    private Catalog catalog;

    @GetMapping("/customers")
    public Page<Customer> page(Pageable pageable) {
        return repository.findAll(pageable);
    }

    @GetMapping("/customers/count")
    public long count() {
        return repository.findAll().size();
    }

    @GetMapping("/catalog")
    public List<Item> items() {
        return catalog.findAll();
    }

    public List<Customer> notAHandler() {
        return repository.findAll();
    }
}
