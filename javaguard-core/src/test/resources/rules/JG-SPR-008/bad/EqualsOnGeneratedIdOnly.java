import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

@Entity
class EqualsOnGeneratedIdOnly {
    @Id
    @GeneratedValue
    private Long id;

    private String name;

    @Override
    public boolean equals(Object other) {
        return other instanceof EqualsOnGeneratedIdOnly o && id.equals(o.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
