package oracle.domain;

import jakarta.persistence.Embeddable;

@Embeddable
public class Address {
    String street;
    String city;
    String zipCode;
}
