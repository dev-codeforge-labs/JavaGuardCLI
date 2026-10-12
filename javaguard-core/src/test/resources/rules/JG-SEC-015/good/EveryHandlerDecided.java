import jakarta.annotation.security.PermitAll;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
class EveryHandlerDecided {
    @PreAuthorize("hasRole('USER')")
    @GetMapping("/a")
    Object a() {
        return null;
    }

    @PreAuthorize("hasRole('USER')")
    @GetMapping("/b")
    Object b() {
        return null;
    }

    @PermitAll
    @GetMapping("/health")
    Object health() {
        return null;
    }

    Object helperWithoutMapping() {
        return null;
    }
}
