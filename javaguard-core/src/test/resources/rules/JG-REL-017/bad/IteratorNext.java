class IteratorNext {
    private jakarta.persistence.EntityManager entityManager;

    Object first() {
        return entityManager.createQuery("select c from Customer c").getResultList().iterator().next();
    }
}
