import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import java.util.List;

@Entity
class EagerOneToMany {
    @OneToMany(mappedBy = "owner", fetch = FetchType.EAGER)
    private List<Object> children;
}
