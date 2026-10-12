import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

class Label {
    @NotNull
    String name;
}

// jhipster *Resource.partialUpdate*: a PATCH body is partial on purpose
@RestController
class PartialUpdateWithoutValid {
    @PatchMapping(value = "/{id}", consumes = { "application/json", "application/merge-patch+json" })
    Label partialUpdate(@PathVariable Long id, @NotNull @RequestBody Label label) {
        return label;
    }

    @RequestMapping(value = "/x/{id}", method = RequestMethod.PATCH)
    Label patch(@PathVariable Long id, @RequestBody Label label) {
        return label;
    }
}
