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

interface ValidQueries extends JpaRepository<Customer, Long> {
    @Query("select c from Customer c where c.email = ?1")
    List<Customer> a(String email);

    @Query("select c from Customer c where c.address.zipCode = :zip and c.createdAt > :since")
    List<Customer> b(String zip, java.time.Instant since);

    @Query("select p from Purchase p join p.customer c where c.lastName = :name and p.total > 0")
    List<PurchaseOrder> c(String name);

    @Query("SELECT C FROM Customer C WHERE C.firstName LIKE 'x.unknown%' ORDER BY C.lastName")
    List<Customer> d();

    @Query("select c from Customer c join fetch c.orders where c.id = :id")
    Customer e(Long id);

    @Query("select count(c) from Customer c where extract(year from c.createdAt) = :y")
    long f(int y);

    @Query("from Customer where active = true")
    List<Customer> g();

    @Query("select c from Customer c, Purchase p where p.customer = c and p.number = :n")
    List<Customer> h(String n);
}
