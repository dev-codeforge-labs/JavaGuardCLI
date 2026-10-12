package oracle.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Product {
    @Id
    String sku;

    String name;

    boolean loggedIn;
}
