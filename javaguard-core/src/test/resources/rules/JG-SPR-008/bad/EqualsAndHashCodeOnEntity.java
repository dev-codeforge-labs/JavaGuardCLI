import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.EqualsAndHashCode;

@Entity
@EqualsAndHashCode
class EqualsAndHashCodeOnEntity {
    @Id
    private Long id;
}
