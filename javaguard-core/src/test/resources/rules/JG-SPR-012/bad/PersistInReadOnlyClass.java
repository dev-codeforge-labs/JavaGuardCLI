import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
class PersistInReadOnlyClass {
    private EntityManager entityManager;

    public void register(Object order) {
        entityManager.persist(order);
    }
}
