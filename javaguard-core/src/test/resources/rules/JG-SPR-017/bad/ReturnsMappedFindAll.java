import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReturnsMappedFindAll {
    private CustomerRepository repository;

    @GetMapping("/customers")
    public List<CustomerDto> all() {
        List<Customer> customers = repository.findAll();
        return customers.stream().map(CustomerDto::from).toList();
    }
}
