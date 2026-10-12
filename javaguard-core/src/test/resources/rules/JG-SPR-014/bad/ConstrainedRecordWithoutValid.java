import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

record SignupRequest(@NotBlank String name, @Email String email) {
}

@RestController
class ConstrainedRecordWithoutValid {
    @PostMapping("/signup")
    public void signup(@RequestBody SignupRequest request) {
    }
}
