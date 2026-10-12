import java.util.List;
import java.util.Optional;

class CheckedFirst {
    private CustomerRepository repository;
    private Cache cache;

    Customer checked(String name) {
        List<Customer> found = repository.findByLastName(name);
        if (found.isEmpty()) {
            return null;
        }
        return found.get(0);
    }

    Optional<Customer> optional(long id) {
        return repository.findById(id);
    }

    Customer notAQuery(String name) {
        return cache.lookup(name).get(0);
    }

    Customer firstOnly(String name) {
        return repository.findFirstByLastName(name);
    }
}
