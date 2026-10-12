import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

@Controller
class ViewController {
    private CustomerRepository repository;

    @GetMapping("/customers")
    public String customers(org.springframework.ui.Model model) {
        model.addAttribute("customers", repository.findAll());
        return "customers";
    }
}
