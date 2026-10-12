package oracle.domain;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Transient;

import java.time.LocalDate;
import java.util.List;

@Entity
public class Customer extends BaseEntity {
    String firstName;
    String lastName;
    String email;
    boolean active;
    int age;
    String URL;
    LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    Status status;

    @Embedded
    Address address;

    @OneToMany(mappedBy = "customer")
    List<PurchaseOrder> orders;

    @Transient
    String display;
}
