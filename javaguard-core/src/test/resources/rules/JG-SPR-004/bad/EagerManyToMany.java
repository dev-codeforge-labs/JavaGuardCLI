import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToMany;
import java.util.Set;

@Entity
class EagerManyToMany {
    @ManyToMany(fetch = FetchType.EAGER)
    private Set<Object> roles;
}
