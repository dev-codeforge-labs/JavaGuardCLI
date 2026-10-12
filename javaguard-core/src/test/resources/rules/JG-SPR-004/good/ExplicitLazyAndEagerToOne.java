import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import java.util.List;

@Entity
class ExplicitLazyAndEagerToOne {
    @OneToMany(fetch = FetchType.LAZY)
    private List<Object> children;

    @ManyToOne(fetch = FetchType.EAGER)
    private Object owner;
}
