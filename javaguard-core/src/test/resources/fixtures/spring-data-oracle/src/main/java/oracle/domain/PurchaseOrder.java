package oracle.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;

import java.math.BigDecimal;

@Entity(name = "Purchase")
public class PurchaseOrder extends BaseEntity {
    @ManyToOne
    Customer customer;

    BigDecimal total;

    String number;
}
