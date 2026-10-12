import java.util.List;

class ThroughVariable {
    private CustomerRepository repository;

    Customer first(String name) {
        List<Customer> found = repository.findByLastName(name);
        return found.getFirst();
    }
}
