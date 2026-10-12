import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.*;

@Secured("ROLE_ADMIN")
@RestController
class ClassLevelSecured {
    @GetMapping("/x")
    Object x() {
        return null;
    }

    @GetMapping("/y")
    Object y() {
        return null;
    }

    @GetMapping("/z")
    Object z() {
        return null;
    }
}

@RestController
class NoConvention {
    @Secured("ROLE_ADMIN")
    @GetMapping("/p")
    Object p() {
        return null;
    }

    @GetMapping("/q")
    Object q() {
        return null;
    }

    @GetMapping("/r")
    Object r() {
        return null;
    }
}

@RestController
class OnlyTwoHandlers {
    @Secured("ROLE_ADMIN")
    @GetMapping("/s")
    Object s() {
        return null;
    }

    @GetMapping("/t")
    Object t() {
        return null;
    }
}
