import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotNull;

@Entity
class JakartaImports {
    @Id
    Long id;

    @NotNull
    String name;
}
