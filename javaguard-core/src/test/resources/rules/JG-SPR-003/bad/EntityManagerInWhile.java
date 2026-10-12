import jakarta.persistence.EntityManager;

class EntityManagerInWhile {
    void load(EntityManager em, long max) {
        long id = 0;
        while (id < max) {
            em.find(Object.class, id++);
        }
    }
}
