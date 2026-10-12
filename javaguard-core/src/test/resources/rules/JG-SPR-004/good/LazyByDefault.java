import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import java.util.List;

@Entity
class LazyByDefault {
    @OneToMany(mappedBy = "owner")
    private List<Object> children;
}
