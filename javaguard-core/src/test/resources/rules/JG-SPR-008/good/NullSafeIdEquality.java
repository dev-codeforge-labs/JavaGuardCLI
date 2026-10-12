import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

@Entity
class NullSafeIdEquality {
    @Id
    @GeneratedValue
    private Long id;

    @Override
    public boolean equals(Object other) {
        return other instanceof NullSafeIdEquality o && id != null && id.equals(o.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
