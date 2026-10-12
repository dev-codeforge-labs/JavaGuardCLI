import jakarta.persistence.*;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

@Entity
@NamedQuery(name = "Book.byTitle", query = "select b from Book b where b.name = :title")
class Book {
    @Id
    Long id;
    String title;
}
