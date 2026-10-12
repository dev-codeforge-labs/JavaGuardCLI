import java.util.List;
import org.springframework.web.bind.annotation.*;

// jhipster AuthorityResource.getAllAuthorities
@RestController
class ReferenceTableReturnedWhole {
    private AuthorityRepository authorityRepository;
    private CountryRepository countryRepository;

    @GetMapping("/authorities")
    List<Authority> all() {
        return authorityRepository.findAll();
    }

    @GetMapping("/countries")
    List<Country> countries() {
        return countryRepository.findAll();
    }
}
