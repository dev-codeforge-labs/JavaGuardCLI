import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

record CommentRequest(@NotBlank String text) {
}

record PlainRequest(String text) {
}

@RestController
class ValidatedBodies {
    @PostMapping("/a")
    public void a(@Valid @RequestBody CommentRequest request) {
    }

    @PostMapping("/b")
    public void b(@RequestBody @Validated CommentRequest request) {
    }

    @PostMapping("/c")
    public void c(@RequestBody PlainRequest request) {
    }

    @PostMapping("/d")
    public void d(@RequestBody List<CommentRequest> requests) {
    }

    @PostMapping("/e")
    public void e(@RequestBody ExternalRequest request) {
    }
}
