import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
class DataOnEntity {
    @Id
    private Long id;
    private String name;
}
