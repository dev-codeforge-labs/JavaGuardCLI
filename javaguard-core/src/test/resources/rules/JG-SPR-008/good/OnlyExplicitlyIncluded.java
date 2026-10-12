import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.EqualsAndHashCode;

@Entity
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
class OnlyExplicitlyIncluded {
    @Id
    @EqualsAndHashCode.Include
    private String isbn;
}
