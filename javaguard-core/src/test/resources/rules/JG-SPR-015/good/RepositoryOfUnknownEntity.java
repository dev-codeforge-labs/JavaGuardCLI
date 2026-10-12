import jakarta.persistence.*;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

interface RepositoryOfUnknownEntity extends JpaRepository<Invoice, Long> {
    List<Invoice> findByWhateverItIs(String value);
}
