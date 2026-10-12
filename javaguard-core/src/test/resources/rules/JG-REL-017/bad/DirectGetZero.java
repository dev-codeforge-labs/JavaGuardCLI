import java.util.List;

class DirectGetZero {
    private CustomerRepository repository;

    Customer first(String name) {
        return repository.findByLastName(name).get(0);
    }
}
