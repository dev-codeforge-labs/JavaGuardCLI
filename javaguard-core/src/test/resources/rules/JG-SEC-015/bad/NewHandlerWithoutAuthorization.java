import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
class NewHandlerWithoutAuthorization {
    @PreAuthorize("hasRole('USER')")
    @GetMapping("/{id}")
    Object get(@PathVariable long id) {
        return null;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    void delete(@PathVariable long id) {
    }

    @PostMapping("/{id}/close")
    void close(@PathVariable long id) {
    }
}
