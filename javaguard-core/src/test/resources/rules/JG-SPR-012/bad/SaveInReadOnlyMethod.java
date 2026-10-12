import org.springframework.transaction.annotation.Transactional;

class SaveInReadOnlyMethod {
    private final CustomerRepository repository;

    SaveInReadOnlyMethod(CustomerRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Customer touch(long id) {
        Customer customer = repository.findById(id).orElseThrow();
        customer.setLastSeen(System.currentTimeMillis());
        return repository.save(customer);
    }
}
