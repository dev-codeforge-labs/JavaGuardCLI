import jakarta.persistence.*;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

@MappedSuperclass
abstract class BaseEntity {
    @Id
    Long id;
    java.time.Instant createdAt;
}

@Embeddable
class Address {
    String street;
    String city;
    String zipCode;
}

@Entity
class Customer extends BaseEntity {
    String firstName;
    String lastName;
    String email;
    boolean active;
    int age;
    @Embedded
    Address address;
    @OneToMany(mappedBy = "customer")
    java.util.List<PurchaseOrder> orders;
    @Transient
    String display;
}

@Entity(name = "Purchase")
class PurchaseOrder extends BaseEntity {
    @ManyToOne
    Customer customer;
    java.math.BigDecimal total;
    String number;
}

interface NotDerivedQueries extends JpaRepository<Customer, Long> {
    @Query("select c from Customer c where c.email = ?1")
    List<Customer> findByWhatever(String email);

    default List<Customer> findByNothing() {
        return List.of();
    }

    List<Customer> searchAnything(String text);
}
