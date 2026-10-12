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

interface ValidDerivedQueries extends JpaRepository<Customer, Long> {
    List<Customer> findByFirstName(String firstName);
    List<Customer> findByFirstNameAndLastName(String firstName, String lastName);
    List<Customer> findByLastNameIgnoreCase(String lastName);
    List<Customer> findDistinctByLastNameOrFirstNameAllIgnoreCase(String a, String b);
    List<Customer> findByAddressZipCode(String zip);
    List<Customer> findByAddress_ZipCode(String zip);
    List<Customer> findByAgeBetween(int from, int to);
    List<Customer> findByAgeGreaterThanEqual(int age);
    List<Customer> findByEmailIsNull();
    List<Customer> findByActiveTrue();
    List<Customer> findTop10ByOrderByLastNameAsc();
    List<Customer> findFirst3ByActiveOrderByCreatedAtDesc(boolean active);
    List<Customer> findByOrdersNumber(String number);
    long countByActiveFalse();
    boolean existsByEmail(String email);
    void deleteByEmail(String email);
    Page<Customer> findByLastName(String lastName, Pageable pageable);
    List<Customer> findByLastName(String lastName, Sort sort);
    List<Customer> findByIdIn(List<Long> ids);
    List<Customer> findByCreatedAtBefore(java.time.Instant instant);
    Optional<Customer> findById(Long id);
    <T> List<T> findByLastName(String lastName, Class<T> type);
}
