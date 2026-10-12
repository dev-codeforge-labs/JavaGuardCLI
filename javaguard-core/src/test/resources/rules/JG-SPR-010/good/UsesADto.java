import jakarta.persistence.Entity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class UsesADto {
    @PostMapping("/people")
    public PersonDto create(@RequestBody PersonDto dto) {
        return dto;
    }
}

record PersonDto(String name) {
}

@Entity
class Person {
}
