import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
class WriteOutsideReadOnly {
    private CustomerRepository repository;
    private Cache cache;

    public Iterable<Customer> all() {
        return repository.findAll();
    }

    @Transactional
    public Customer save(Customer customer) {
        return repository.save(customer);
    }

    public void remember(Customer customer) {
        cache.save(customer);
    }

    @Transactional(readOnly = false)
    public void delete(Customer customer) {
        repository.delete(customer);
    }
}
