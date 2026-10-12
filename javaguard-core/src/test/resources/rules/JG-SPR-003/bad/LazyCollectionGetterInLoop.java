import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import java.util.List;

class LazyCollectionGetterInLoop {
    int count(List<Customer> customers) {
        int total = 0;
        for (Customer customer : customers) {
            total += customer.getOrders().size();
        }
        return total;
    }
}

@Entity
class Customer {
    @OneToMany
    private List<Object> orders;

    List<Object> getOrders() {
        return orders;
    }
}
