import jakarta.persistence.Entity;

class EntityOutsideAController {
    Product find() {
        return new Product();
    }
}

@Entity
class Product {
}
